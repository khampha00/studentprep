package com.studentprep.ingestion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.HttpClientErrorException;
import java.util.Map;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class LlmStructuringService {

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    
    @Value("${LLM_API_KEY:}")
    private String geminiApiKey;

    @Value("${gemini.model}")
    private String geminiModel;

    public LlmStructuringService(RestClient.Builder restClientBuilder, ObjectMapper objectMapper) {
        this.restClient = restClientBuilder.build();
        this.objectMapper = objectMapper;
    }

    public JsonNode structureChunk(String chunk, String warning) throws Exception {
        if (geminiApiKey == null || geminiApiKey.isEmpty()) {
            throw new IllegalStateException("LLM_API_KEY is not configured.");
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

        String responseStr = restClient.post()
                .uri("https://generativelanguage.googleapis.com/v1beta/models/" + geminiModel + ":generateContent?key=" + geminiApiKey)
                .header("Content-Type", "application/json")
                .body(requestBody)
                .retrieve()
                .body(String.class);

        JsonNode rootNode = objectMapper.readTree(responseStr);
        String llmOutput = rootNode.path("candidates").get(0).path("content").path("parts").get(0).path("text").asText();
        
        llmOutput = llmOutput.replaceAll("^```json", "").replaceAll("```$", "").trim();
        System.out.println("LLM OUTPUT LENGTH: " + llmOutput.length());
        System.out.println("LLM OUTPUT PREVIEW: " + llmOutput.substring(0, Math.min(200, llmOutput.length())));
        
        if (!llmOutput.endsWith("]")) {
            Matcher matcher = Pattern.compile("\\{\\s*\"type\".*?\"is_follow_up\"\\s*:\\s*(true|false)\\s*\\}", Pattern.DOTALL).matcher(llmOutput);
            StringBuilder salvaged = new StringBuilder("[");
            boolean first = true;
            while (matcher.find()) {
                if (!first) salvaged.append(",");
                salvaged.append(matcher.group());
                first = false;
            }
            salvaged.append("]");
            llmOutput = salvaged.toString();
        }
        
        return objectMapper.readTree(llmOutput);
    }
}
