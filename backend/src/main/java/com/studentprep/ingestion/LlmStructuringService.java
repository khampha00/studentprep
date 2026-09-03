package com.studentprep.ingestion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import java.util.Map;
import java.util.List;

@Service
public class LlmStructuringService {

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    
    @Value("${LLM_API_KEY:}")
    private String geminiApiKey;

    public LlmStructuringService(RestClient.Builder restClientBuilder, ObjectMapper objectMapper) {
        this.restClient = restClientBuilder.build();
        this.objectMapper = objectMapper;
    }

    public JsonNode structureMarkdown(String markdown) {
        if (geminiApiKey == null || geminiApiKey.isEmpty()) {
            throw new IllegalStateException("LLM_API_KEY is not configured.");
        }

        String promptTemplate = "You are an AI tasked with converting raw exam PDF markdown into strict JSON format. " +
                "Extract all questions, options, and correct answers (if an answer key is provided in the text). " +
                "CRITICAL: DO NOT SUMMARIZE OR SKIP ANY QUESTIONS. YOU MUST EXTRACT EVERY SINGLE QUESTION PRESENT IN THE MARKDOWN. " +
                "CRITICAL: ALL math formulas, equations, variables, and expressions MUST be formatted in strict LaTeX and wrapped in inline `$` or block `$$` delimiters so they can be rendered via KaTeX. For example, instead of '2^s3cm', output '$2\\sqrt{3}\\text{cm}$'. " +
                "Output ONLY a JSON array of questions matching this exact schema: " +
                "[{ type: 'MCQ', text: '...', options: { 'A': '...', 'B': '...' }, correctOption: 'A', assets: [] }] \n\n" +
                "Markdown:\n";

        List<JsonNode> allQuestions = new java.util.ArrayList<>();
        
        // Find natural split points (e.g., newlines followed by a number and a dot)
        List<Integer> splitPoints = new java.util.ArrayList<>();
        splitPoints.add(0);
        java.util.regex.Matcher spMatcher = java.util.regex.Pattern.compile("\\n\\d+\\.\\s").matcher(markdown);
        while (spMatcher.find()) {
            splitPoints.add(spMatcher.start());
        }
        splitPoints.add(markdown.length());

        int chunkSize = 2500;
        int currentStart = 0;
        
        while (currentStart < markdown.length()) {
            int targetEnd = Math.min(currentStart + chunkSize, markdown.length()); System.out.println("Processing chunk from " + currentStart + " to " + targetEnd);
            int bestSplit = currentStart;
            
            // Find the largest split point within the targetEnd
            for (int sp : splitPoints) {
                if (sp > currentStart && sp <= targetEnd) {
                    bestSplit = sp;
                }
            }
            
            // Fallback: if no split point found in range, take the next available one
            if (bestSplit == currentStart) {
                for (int sp : splitPoints) {
                    if (sp > targetEnd) {
                        bestSplit = sp;
                        break;
                    }
                }
                if (bestSplit == currentStart) bestSplit = targetEnd;
            }
            
            String chunk = markdown.substring(currentStart, bestSplit);
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

            boolean success = false;
            int retries = 3;
            for (int r = 0; r < retries; r++) {
                try {
                    String responseStr = restClient.post()
                            .uri("https://generativelanguage.googleapis.com/v1beta/models/gemini-3.6-flash:generateContent?key=" + geminiApiKey)
                            .header("Content-Type", "application/json")
                            .body(requestBody)
                            .retrieve()
                            .body(String.class);

                    JsonNode rootNode = objectMapper.readTree(responseStr);
                    String llmOutput = rootNode.path("candidates").get(0).path("content").path("parts").get(0).path("text").asText();
                    
                    llmOutput = llmOutput.replaceAll("^```json", "").replaceAll("```$", "").trim();
                    
                    if (!llmOutput.endsWith("]")) {
                        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\\{\\s*\"type\".*?\"assets\"\\s*:\\s*\\[.*?\\]\\s*\\}", java.util.regex.Pattern.DOTALL).matcher(llmOutput);
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
                    
                    JsonNode chunkQuestions = objectMapper.readTree(llmOutput);
                    if (chunkQuestions.isArray()) {
                        for (JsonNode q : chunkQuestions) {
                            allQuestions.add(q);
                        }
                    }
                    success = true;
                    break; // break retry loop
                } catch (Exception e) {
                    System.err.println("Failed to process chunk (attempt " + (r+1) + "): " + e.getMessage());
                    if (r == retries - 1) {
                        throw new RuntimeException("LLM Extraction failed after retries: " + e.getMessage());
                    }
                    try { Thread.sleep(2000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                }
            }
            
            currentStart = bestSplit;
        }
        
        return objectMapper.valueToTree(allQuestions);
    }
}
