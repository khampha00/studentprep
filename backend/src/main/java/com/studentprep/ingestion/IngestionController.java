package com.studentprep.ingestion;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.client.RestClient;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.http.MediaType;
import java.util.Map;
import java.util.UUID;

import com.studentprep.ingestion.job.IngestionJob;
import com.studentprep.ingestion.job.IngestionJobRepository;
import com.studentprep.ingestion.job.IngestionJobStatus;
import com.studentprep.questionbank.Subject;
import com.studentprep.questionbank.SubjectRepository;

@RestController
@RequestMapping("/api/v1/admin/ingest")
public class IngestionController {

    private final RestClient restClient;
    private final SubjectRepository subjectRepository;
    private final S3Service s3Service;
    private final AsyncIngestionWorker asyncIngestionWorker;
    private final IngestionJobRepository ingestionJobRepository;

    public IngestionController(RestClient.Builder restClientBuilder, 
                               SubjectRepository subjectRepository, 
                               S3Service s3Service,
                               AsyncIngestionWorker asyncIngestionWorker,
                               IngestionJobRepository ingestionJobRepository) {
        this.restClient = restClientBuilder.build();
        this.subjectRepository = subjectRepository;
        this.s3Service = s3Service;
        this.asyncIngestionWorker = asyncIngestionWorker;
        this.ingestionJobRepository = ingestionJobRepository;
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
    public ResponseEntity<Map<String, UUID>> ingestPdf(@RequestParam("file") MultipartFile file, @RequestParam("subjectId") UUID subjectId) {
        try {
            Subject subject = subjectRepository.findById(subjectId)
                    .orElseThrow(() -> new RuntimeException("Subject not found"));

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

            IngestionJob job = new IngestionJob();
            job.setSubject(subject);
            job.setStatus(IngestionJobStatus.PENDING);
            ingestionJobRepository.save(job);

            asyncIngestionWorker.processMarkdown(job.getId(), subject.getId(), markdown);

            return ResponseEntity.accepted().body(Map.of("jobId", job.getId()));
            
        } catch (Exception e) {
            e.printStackTrace();
            throw new RuntimeException("Ingestion initialization failed: " + e.getMessage());
        }
    }

    @GetMapping("/jobs/{jobId}")
    public ResponseEntity<IngestionJob> getJobStatus(@PathVariable UUID jobId) {
        return ingestionJobRepository.findById(jobId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}
