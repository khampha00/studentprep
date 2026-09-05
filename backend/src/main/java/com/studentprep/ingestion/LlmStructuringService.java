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

    public JsonNode structureChunk(String chunk) throws Exception {
        if (geminiApiKey == null || geminiApiKey.isEmpty()) {
            throw new IllegalStateException("LLM_API_KEY is not configured.");
        }

        String promptTemplate = "You are an AI tasked with converting raw exam PDF markdown into strict JSON format. " +
                "Extract all questions, options, and correct answers (if an answer key is provided in the text). " +
                "CRITICAL: DO NOT SUMMARIZE OR SKIP ANY QUESTIONS. YOU MUST EXTRACT EVERY SINGLE QUESTION PRESENT IN THE MARKDOWN. " +
                "CRITICAL: ALL math formulas, equations, variables, and expressions MUST be formatted in strict LaTeX and wrapped in inline `$` or block `$$` delimiters so they can be rendered via KaTeX. For example, instead of '2^s3cm', output '$2\\\\sqrt{3}\\\\text{cm}$'. " +
                "Output ONLY a JSON array of questions matching this exact schema: " +
                "[{ type: 'MCQ', text: '...', options: { 'A': '...', 'B': '...' }, correctOption: 'A', assets: [] }] \\n\\n" +
                "Markdown:\\n";

        String prompt = promptTemplate + chunk;

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
        
        if (!llmOutput.endsWith("]")) {
            Matcher matcher = Pattern.compile("\\{\\s*\"type\".*?\"assets\"\\s*:\\s*\\[.*?\\]\\s*\\}", Pattern.DOTALL).matcher(llmOutput);
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
