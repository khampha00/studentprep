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
import com.studentprep.common.ApiResponse;

import com.studentprep.ingestion.job.IngestionJob;
import com.studentprep.ingestion.job.IngestionJobRepository;
import com.studentprep.ingestion.job.IngestionJobStatus;
import com.studentprep.questionbank.Subject;
import com.studentprep.questionbank.SubjectRepository;

@RestController
@RequestMapping("/api/v1/admin/ingestion")
public class IngestionController {

    private final DoclingService doclingService;
    private final SubjectRepository subjectRepository;
    private final S3Service s3Service;
    private final AsyncIngestionWorker asyncIngestionWorker;
    private final IngestionJobRepository ingestionJobRepository;

    public IngestionController(DoclingService doclingService, 
                               SubjectRepository subjectRepository, 
                               S3Service s3Service,
                               AsyncIngestionWorker asyncIngestionWorker,
                               IngestionJobRepository ingestionJobRepository) {
        this.doclingService = doclingService;
        this.subjectRepository = subjectRepository;
        this.s3Service = s3Service;
        this.asyncIngestionWorker = asyncIngestionWorker;
        this.ingestionJobRepository = ingestionJobRepository;
    }

    @PostMapping("/assets")
    public ResponseEntity<ApiResponse<Map<String, String>>> uploadAsset(@RequestParam("file") MultipartFile file) {
        try {
            String url = s3Service.uploadImage(file);
            return ResponseEntity.ok(ApiResponse.of(Map.of("url", url)));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(ApiResponse.of(Map.of("error", e.getMessage())));
        }
    }

    @PostMapping("/pdf")
    public ResponseEntity<ApiResponse<Map<String, Object>>> ingestPdf(
            @RequestParam("file") MultipartFile file, 
            @RequestParam("subjectId") UUID subjectId,
            @RequestParam(value = "year", required = false) Integer year) {
        if (!"application/pdf".equals(file.getContentType())) {
            return ResponseEntity.badRequest().body(ApiResponse.of(Map.of("error", "Only PDF files are allowed")));
        }
        if (file.getSize() > 10 * 1024 * 1024) {
            return ResponseEntity.badRequest().body(ApiResponse.of(Map.of("error", "File size must be less than 10MB")));
        }

        try {
            byte[] header = new byte[5];
            java.io.InputStream is = file.getInputStream();
            if (is.read(header) < 5 || !new String(header).equals("%PDF-")) {
                return ResponseEntity.badRequest().body(ApiResponse.of(Map.of("error", "Invalid file format, magic bytes do not match PDF")));
            }
        } catch (java.io.IOException e) {
            return ResponseEntity.status(500).body(ApiResponse.of(Map.of("error", "Could not read file")));
        }

        try {
            Subject subject = subjectRepository.findById(subjectId)
                    .orElseThrow(() -> new RuntimeException("Subject not found"));

            String markdown = doclingService.parsePdf(file.getResource());

            IngestionJob job = new IngestionJob();
            job.setSubject(subject);
            job.setStatus(IngestionJobStatus.PENDING);
            ingestionJobRepository.save(job);

            asyncIngestionWorker.processMarkdown(job.getId(), subject.getId(), markdown);

            return ResponseEntity.accepted().body(ApiResponse.of(Map.of("jobId", job.getId())));
            
        } catch (Exception e) {
            e.printStackTrace();
            throw new RuntimeException("Ingestion initialization failed: " + e.getMessage());
        }
    }

    @GetMapping("/jobs/{jobId}")
    public ResponseEntity<ApiResponse<IngestionJob>> getJobStatus(@PathVariable UUID jobId) {
        return ingestionJobRepository.findById(jobId)
                .map(job -> ResponseEntity.ok(ApiResponse.of(job)))
                .orElse(ResponseEntity.notFound().build());
    }
}
