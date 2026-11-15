package com.studentprep.ingestion;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Structures extracted markdown chunks into question JSON payloads using Gemini,
 * enforcing bounded HTTP timeouts, sending credentials exclusively via headers,
 * salvaging truncated JSON arrays, and sanitizing upstream errors.
 */
@Service
public class LlmStructuringService {

    private static final Logger log = LoggerFactory.getLogger(LlmStructuringService.class);
    private static final String DEFAULT_GEMINI_GENERATE_URL =
            "https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent";
    private static final int DEFAULT_CONNECT_TIMEOUT_MS = 10_000;
    private static final long MIN_TIMEOUT_MS = 100L;

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String geminiApiKey;
    private final String geminiModel;
    private final String geminiGenerateUrl;

    @Autowired
    public LlmStructuringService(
            RestClient.Builder restClientBuilder,
            ObjectMapper objectMapper,
            @Value("${gemini.api-key:}") String geminiApiKey,
            @Value("${gemini.model:gemini-3.5-flash-lite}") String geminiModel,
            @Value("${gemini.timeout-ms:60000}") long timeoutMs) {
        this(
                restClientBuilder,
                objectMapper,
                geminiApiKey,
                geminiModel,
                timeoutMs,
                DEFAULT_GEMINI_GENERATE_URL
        );
    }

    /**
     * Preserves caller-configured {@link RestClient.Builder} factories (such as {@code MockRestServiceServer}).
     */
    public LlmStructuringService(
            RestClient.Builder restClientBuilder,
            ObjectMapper objectMapper,
            String geminiApiKey,
            String geminiModel) {
        this.restClient = restClientBuilder.build();
        this.objectMapper = objectMapper;
        this.geminiApiKey = geminiApiKey;
        this.geminiModel = geminiModel;
        this.geminiGenerateUrl = DEFAULT_GEMINI_GENERATE_URL;
    }

    LlmStructuringService(
            RestClient.Builder restClientBuilder,
            ObjectMapper objectMapper,
            String geminiApiKey,
            String geminiModel,
            long timeoutMs,
            String geminiGenerateUrl) {
        SimpleClientHttpRequestFactory requestFactory = createRequestFactory(timeoutMs);
        this.restClient = restClientBuilder.requestFactory(requestFactory).build();
        this.objectMapper = objectMapper;
        this.geminiApiKey = geminiApiKey;
        this.geminiModel = geminiModel;
        this.geminiGenerateUrl = geminiGenerateUrl;
    }

    static SimpleClientHttpRequestFactory createRequestFactory(long readTimeoutMs) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        int boundedReadTimeoutMs = (int) Math.max(MIN_TIMEOUT_MS, Math.min(readTimeoutMs, Integer.MAX_VALUE));
        int boundedConnectTimeoutMs = Math.min(DEFAULT_CONNECT_TIMEOUT_MS, boundedReadTimeoutMs);
        requestFactory.setConnectTimeout(boundedConnectTimeoutMs);
        requestFactory.setReadTimeout(boundedReadTimeoutMs);
        return requestFactory;
    }

    public JsonNode structureChunk(String chunk, String warning) throws Exception {
        if (geminiApiKey == null || geminiApiKey.isBlank()) {
            throw new IllegalStateException("Gemini API key is not configured.");
        }
        if (geminiModel == null || geminiModel.isBlank()) {
            throw new IllegalStateException("Gemini model is not configured.");
        }

        String promptTemplate = "You are an AI tasked with converting raw exam PDF markdown into strict JSON format. " +
                "Extract all questions, options, and correct answers (if an answer key is provided in the text). " +
                "CRITICAL: DO NOT SUMMARIZE OR SKIP ANY QUESTIONS. YOU MUST EXTRACT EVERY SINGLE QUESTION PRESENT IN THE MARKDOWN. " +
                "CRITICAL: ALL math formulas, equations, variables, and expressions MUST be formatted in strict LaTeX and wrapped in inline `$` or block `$$` delimiters so they can be rendered via KaTeX. " +
                "However, DO NOT convert standard punctuation like ellipses (...) or blanks (____) into LaTeX `\\\\dots`. Keep them as standard text periods. " +
                "IMPORTANT CONTEXT RULES: If you encounter a SUBSTANTIAL table, diagram, or long text passage that applies to multiple questions, place that entire markdown table/diagram/passage into the `shared_context` field of the FIRST question it applies to. " +
                "For 'Lexis and Structure' or 'fill-in-the-blank' passages where options are presented in a table for numbered gaps, extract the entire passage as the `shared_context` for the first question, and for each numbered gap, create a question where the `text` is simply 'Fill in gap [Number]'. " +
                "CRITICAL WARNING: DO NOT treat simple instructions (e.g., 'Choose the correct option', 'Fill in the gap in the following sentences') as a shared context! Only treat actual reading passages, data tables, or diagrams as shared context. " +
                "For the follow-up questions that rely on the same passage, leave `shared_context` blank and set `is_follow_up: true`. " +
                "Output ONLY a JSON array of questions matching this exact schema: " +
                "[{\"type\": \"MCQ\", \"text\": \"...\", \"options\": {\"A\": \"...\", \"B\": \"...\"}, \"correctOption\": \"A\", \"assets\": [], \"shared_context\": \"...\", \"is_follow_up\": false, \"questionNumber\": \"1\"}] \\n\\n" +
                "Markdown:\\n";

        String prompt = promptTemplate + (warning != null ? warning + "\\n\\n" : "") + chunk;

        Map<String, Object> requestBody = Map.of(
                "contents", List.of(
                        Map.of("parts", List.of(
                                Map.of("text", prompt)
                        ))
                ),
                "generationConfig", Map.of(
                        "maxOutputTokens", 8192,
                        "responseMimeType", "application/json",
                        "responseSchema", buildQuestionArrayResponseSchema()
                )
        );

        String responseStr;
        try {
            responseStr = restClient.post()
                    .uri(geminiGenerateUrl, geminiModel)
                    .header("Content-Type", "application/json")
                    .header("x-goog-api-key", geminiApiKey)
                    .body(requestBody)
                    .retrieve()
                    .body(String.class);
        } catch (RestClientResponseException ex) {
            int statusCode = ex.getStatusCode().value();
            log.warn("Gemini API request failed with HTTP status {}", statusCode);
            throw new IllegalStateException("Gemini API request failed with HTTP " + statusCode);
        } catch (RestClientException ex) {
            String sanitized = ErrorSanitizer.sanitizeThrowable(ex, geminiApiKey);
            log.warn("Gemini API communication failure: {}", sanitized);
            throw new IllegalStateException("Gemini API communication failure: " + sanitized);
        }

        if (responseStr == null || responseStr.isBlank()) {
            throw new IllegalStateException("Gemini API returned an empty response.");
        }

        JsonNode rootNode = objectMapper.readTree(responseStr);
        JsonNode candidates = rootNode.path("candidates");
        if (!candidates.isArray() || candidates.isEmpty()) {
            throw new IllegalStateException("Gemini API returned no candidates.");
        }
        JsonNode parts = candidates.get(0).path("content").path("parts");
        if (!parts.isArray() || parts.isEmpty()) {
            throw new IllegalStateException("Gemini API returned no content parts.");
        }

        String answerText = extractAnswerText(parts);
        String llmOutput = stripMarkdownFences(answerText);
        if (llmOutput.isBlank()) {
            throw new IllegalStateException("Gemini API returned blank structured content.");
        }
        log.debug("Received structured LLM output ({} chars)", llmOutput.length());

        return parseOrSalvageArray(llmOutput);
    }

    private static Map<String, Object> buildQuestionArrayResponseSchema() {
        Map<String, Object> optionsProperties = Map.of(
                "A", Map.of("type", "STRING"),
                "B", Map.of("type", "STRING"),
                "C", Map.of("type", "STRING"),
                "D", Map.of("type", "STRING"),
                "E", Map.of("type", "STRING")
        );

        Map<String, Object> itemProperties = Map.of(
                "type", Map.of("type", "STRING"),
                "text", Map.of("type", "STRING"),
                "options", Map.of(
                        "type", "OBJECT",
                        "properties", optionsProperties
                ),
                "correctOption", Map.of("type", "STRING", "nullable", true),
                "assets", Map.of(
                        "type", "ARRAY",
                        "items", Map.of("type", "STRING")
                ),
                "shared_context", Map.of("type", "STRING", "nullable", true),
                "is_follow_up", Map.of("type", "BOOLEAN"),
                "questionNumber", Map.of("type", "STRING", "nullable", true)
        );

        return Map.of(
                "type", "ARRAY",
                "items", Map.of(
                        "type", "OBJECT",
                        "properties", itemProperties,
                        "required", List.of("type", "text", "options")
                )
        );
    }

    private static String extractAnswerText(JsonNode parts) {
        StringBuilder answer = new StringBuilder();
        for (JsonNode part : parts) {
            if (isThoughtPart(part)) {
                continue;
            }
            if (part.hasNonNull("text")) {
                answer.append(part.path("text").asText(""));
            }
        }
        String result = answer.toString();
        if (result.isBlank()) {
            throw new IllegalStateException("Gemini API returned no answer text parts.");
        }
        return result;
    }

    private static boolean isThoughtPart(JsonNode part) {
        JsonNode thoughtNode = part.path("thought");
        if (thoughtNode.isBoolean()) {
            return thoughtNode.asBoolean(false);
        }
        return !thoughtNode.isMissingNode() && !thoughtNode.isNull() && !thoughtNode.asText("").isBlank();
    }

    static String stripMarkdownFences(String rawOutput) {
        if (rawOutput == null) {
            return "";
        }
        String trimmed = rawOutput.trim();
        if (trimmed.startsWith("```")) {
            trimmed = trimmed.replaceFirst("^```[a-zA-Z0-9_-]*\\s*", "");
        }
        if (trimmed.endsWith("```")) {
            trimmed = trimmed.replaceFirst("\\s*```$", "");
        }
        return trimmed.trim();
    }

    JsonNode parseOrSalvageArray(String llmOutput) throws JsonProcessingException {
        if (llmOutput == null || llmOutput.isBlank()) {
            return objectMapper.createArrayNode();
        }

        JsonNode direct = tryParseDirectOutput(llmOutput);
        if (direct != null) {
            return direct;
        }

        String salvaged = salvageTruncatedArray(llmOutput);
        JsonNode salvagedNode = objectMapper.readTree(salvaged);
        if (salvagedNode.isEmpty()) {
            throw new IllegalStateException(
                    "LLM returned unparseable output with 0 salvageable question objects.");
        }
        log.warn("Salvaged {} complete question object(s) from truncated or non-standard LLM output",
                salvagedNode.size());
        return salvagedNode;
    }

    private JsonNode tryParseDirectOutput(String llmOutput) {
        if (llmOutput == null || llmOutput.isBlank()) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(llmOutput);
            if (node == null) {
                return null;
            }
            if (node.isArray()) {
                return node;
            }
            if (node.isObject()) {
                return objectMapper.createArrayNode().add(node);
            }
            return null;
        } catch (JsonProcessingException ex) {
            return null;
        }
    }

    /**
     * Scans a potentially truncated JSON array using string- and escape-aware balanced-brace
     * tracking so complete question objects are preserved regardless of key ordering.
     */
    String salvageTruncatedArray(String rawOutput) {
        if (rawOutput == null || rawOutput.isBlank()) {
            return "[]";
        }

        int searchFrom = 0;
        while (searchFrom < rawOutput.length()) {
            int arrayStart = rawOutput.indexOf('[', searchFrom);
            if (arrayStart < 0) {
                return "[]";
            }

            List<String> salvagedObjects = new ArrayList<>();
            int objectDepth = 0;
            int objectStart = -1;
            boolean inString = false;
            boolean escaped = false;
            int nextSearchFrom = rawOutput.length();

            for (int i = arrayStart + 1; i < rawOutput.length(); i++) {
                char c = rawOutput.charAt(i);
                if (inString) {
                    if (escaped) {
                        escaped = false;
                    } else if (c == '\\') {
                        escaped = true;
                    } else if (c == '"') {
                        inString = false;
                    }
                    continue;
                }

                if (c == '"') {
                    inString = true;
                } else if (c == '{') {
                    if (objectDepth == 0) {
                        objectStart = i;
                    }
                    objectDepth++;
                } else if (c == '}') {
                    if (objectDepth > 0) {
                        objectDepth--;
                        if (objectDepth == 0 && objectStart >= 0) {
                            String candidate = rawOutput.substring(objectStart, i + 1);
                            if (isValidJsonObject(candidate)) {
                                salvagedObjects.add(candidate);
                            }
                            objectStart = -1;
                        }
                    }
                } else if (c == ']' && objectDepth == 0) {
                    nextSearchFrom = i + 1;
                    break;
                }
            }

            if (!salvagedObjects.isEmpty()) {
                return "[" + String.join(",", salvagedObjects) + "]";
            }
            searchFrom = nextSearchFrom;
        }

        return "[]";
    }

    private boolean isValidJsonObject(String candidate) {
        try {
            JsonNode node = objectMapper.readTree(candidate);
            return node != null && node.isObject() && !node.isEmpty();
        } catch (JsonProcessingException ex) {
            return false;
        }
    }
}
