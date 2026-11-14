package com.studentprep.ingestion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Structures extracted markdown chunks into question JSON payloads using Gemini,
 * sending credentials exclusively via headers and sanitizing upstream errors.
 */
@Service
public class LlmStructuringService {

    private static final Logger log = LoggerFactory.getLogger(LlmStructuringService.class);
    private static final String GEMINI_GENERATE_URL =
            "https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent";

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String geminiApiKey;
    private final String geminiModel;

    public LlmStructuringService(
            RestClient.Builder restClientBuilder,
            ObjectMapper objectMapper,
            @Value("${gemini.api-key:}") String geminiApiKey,
            @Value("${gemini.model:gemini-3.5-flash-lite}") String geminiModel) {
        this.restClient = restClientBuilder.build();
        this.objectMapper = objectMapper;
        this.geminiApiKey = geminiApiKey;
        this.geminiModel = geminiModel;
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
                "[{ type: 'MCQ', text: '...', options: { 'A': '...', 'B': '...' }, correctOption: 'A', assets: [], shared_context: '...', is_follow_up: false, questionNumber: '1' }] \\n\\n" +
                "Markdown:\\n";

        String prompt = promptTemplate + (warning != null ? warning + "\\n\\n" : "") + chunk;

        Map<String, Object> requestBody = Map.of(
                "contents", List.of(
                        Map.of("parts", List.of(
                                Map.of("text", prompt)
                        ))
                ),
                "generationConfig", Map.of(
                        "maxOutputTokens", 8192
                )
        );

        String responseStr;
        try {
            responseStr = restClient.post()
                    .uri(GEMINI_GENERATE_URL, geminiModel)
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

        String llmOutput = parts.get(0).path("text").asText("");
        llmOutput = llmOutput.replaceAll("^```(?:json)?\\s*", "").replaceAll("```$", "").trim();
        log.debug("Received structured LLM output ({} chars)", llmOutput.length());

        if (!llmOutput.endsWith("]")) {
            Matcher matcher = Pattern.compile("\\{\\s*\"type\".*?\"is_follow_up\"\\s*:\\s*(true|false)\\s*\\}", Pattern.DOTALL)
                    .matcher(llmOutput);
            StringBuilder salvaged = new StringBuilder("[");
            boolean first = true;
            while (matcher.find()) {
                if (!first) {
                    salvaged.append(",");
                }
                salvaged.append(matcher.group());
                first = false;
            }
            salvaged.append("]");
            llmOutput = salvaged.toString();
        }

        return objectMapper.readTree(llmOutput);
    }
}
