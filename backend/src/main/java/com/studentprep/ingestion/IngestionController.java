package com.studentprep.ingestion;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.client.RestClient;
import org.springframework.core.io.Resource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.http.MediaType;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin/ingest")
public class IngestionController {

    private final LlmStructuringService llmStructuringService;
    private final RestClient restClient;
    private final com.studentprep.questionbank.QuestionRepository questionRepository;
    private final S3Service s3Service;

    public IngestionController(LlmStructuringService llmStructuringService, RestClient.Builder restClientBuilder, com.studentprep.questionbank.QuestionRepository questionRepository, S3Service s3Service) {
        this.llmStructuringService = llmStructuringService;
        this.restClient = restClientBuilder.build();
        this.questionRepository = questionRepository;
        this.s3Service = s3Service;
    }

    @PostMapping("/assets")
    public ResponseEntity<Map<String, String>> uploadAsset(@RequestParam("file") MultipartFile file) {
        try {
            String url = s3Service.uploadImage(file);
            return ResponseEntity.ok(Map.of("url", url));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/pdf")
    public ResponseEntity<JsonNode> ingestPdf(@RequestParam("file") MultipartFile file) {
        try {
            // Step 1: Forward MultipartFile to Python docling-parser container
            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            body.add("file", file.getResource());

            String doclingUrl = System.getenv().getOrDefault("DOCLING_PARSER_URL", "http://localhost:8000");

            Map<String, String> response = restClient.post()
                    .uri(doclingUrl + "/parse")
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(body)
                    .retrieve()
                    .body(Map.class);

            String markdown = response.get("markdown");

            // Step 2 & 3: Send Markdown to LLM
            JsonNode structuredQuestions = llmStructuringService.structureMarkdown(markdown);
            
            // Step 4: Save structured JSON to QuestionRepository
            if (structuredQuestions.isArray()) {
                for (JsonNode qNode : structuredQuestions) {
                    com.studentprep.questionbank.Question q = new com.studentprep.questionbank.Question();
                    q.setStatus("DRAFT");
                    q.setSubject("General"); // Can be inferred by LLM later
                    
                    // Convert JsonNode to Map
                    com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                    java.util.Map<String, Object> contentMap = mapper.convertValue(qNode, new com.fasterxml.jackson.core.type.TypeReference<java.util.Map<String, Object>>() {});
                    q.setContent(contentMap);
                    
                    questionRepository.save(q);
                }
            }

            return ResponseEntity.ok(structuredQuestions);
            
        } catch (Exception e) {
            e.printStackTrace();
            throw new RuntimeException("Ingestion failed: " + e.getMessage());
        }
    }
}
