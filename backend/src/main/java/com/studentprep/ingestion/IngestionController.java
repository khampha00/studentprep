package com.studentprep.ingestion;

import com.studentprep.common.ApiResponse;
import com.studentprep.ingestion.job.IngestionJob;
import com.studentprep.ingestion.job.IngestionJobRepository;
import com.studentprep.ingestion.job.IngestionJobStatus;
import com.studentprep.questionbank.Subject;
import com.studentprep.questionbank.SubjectRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import software.amazon.awssdk.core.exception.SdkException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * Administrative endpoints for diagram asset uploads, PDF ingestion, and ingestion job polling.
 */
@RestController
@RequestMapping("/api/v1/admin/ingestion")
@PreAuthorize("hasRole('ADMIN')")
public class IngestionController {

    private static final Logger log = LoggerFactory.getLogger(IngestionController.class);
    private static final long MAX_PDF_BYTES = 10L * 1024L * 1024L;
    private static final String PDF_MAGIC_HEADER = "%PDF-";

    private final DoclingService doclingService;
    private final SubjectRepository subjectRepository;
    private final S3Service s3Service;
    private final AsyncIngestionWorker asyncIngestionWorker;
    private final IngestionJobRepository ingestionJobRepository;
    private final ErrorSanitizer errorSanitizer;

    @Autowired
    public IngestionController(
            DoclingService doclingService,
            SubjectRepository subjectRepository,
            S3Service s3Service,
            AsyncIngestionWorker asyncIngestionWorker,
            IngestionJobRepository ingestionJobRepository,
            ErrorSanitizer errorSanitizer) {
        this.doclingService = doclingService;
        this.subjectRepository = subjectRepository;
        this.s3Service = s3Service;
        this.asyncIngestionWorker = asyncIngestionWorker;
        this.ingestionJobRepository = ingestionJobRepository;
        this.errorSanitizer = errorSanitizer != null ? errorSanitizer : new ErrorSanitizer("", "", "");
    }

    public IngestionController(
            DoclingService doclingService,
            SubjectRepository subjectRepository,
            S3Service s3Service,
            AsyncIngestionWorker asyncIngestionWorker,
            IngestionJobRepository ingestionJobRepository) {
        this(
                doclingService,
                subjectRepository,
                s3Service,
                asyncIngestionWorker,
                ingestionJobRepository,
                new ErrorSanitizer("", "", "")
        );
    }

    @PostMapping("/assets")
    public ResponseEntity<ApiResponse<Map<String, String>>> uploadAsset(@RequestParam("file") MultipartFile file) {
        try {
            String url = s3Service.uploadImage(file);
            return ResponseEntity.ok(ApiResponse.of(Map.of("url", url)));
        } catch (IOException | SdkException ex) {
            log.error("Asset storage upload failed: {}", errorSanitizer.sanitizeException(ex));
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Failed to store uploaded asset");
        }
    }

    @PostMapping("/pdf")
    public ResponseEntity<ApiResponse<Map<String, Object>>> ingestPdf(
            @RequestParam("file") MultipartFile file,
            @RequestParam("subjectId") UUID subjectId,
            @RequestParam(value = "year", required = false) Integer year) {
        validatePdfUpload(file);

        Subject subject = subjectRepository.findById(subjectId)
                .orElseThrow(() -> new NoSuchElementException("Subject not found"));

        String markdown;
        try {
            markdown = doclingService.parsePdf(file.getResource());
        } catch (Exception ex) {
            String sanitized = errorSanitizer.sanitizeException(ex);
            log.error("PDF parsing failed for subject {}: {}", subjectId, sanitized);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "PDF parsing failed: " + sanitized);
        }

        IngestionJob job = new IngestionJob();
        job.setSubject(subject);
        job.setStatus(IngestionJobStatus.PENDING);
        ingestionJobRepository.save(job);

        asyncIngestionWorker.processMarkdown(job.getId(), subject.getId(), markdown);

        return ResponseEntity.accepted().body(ApiResponse.of(Map.of("jobId", job.getId())));
    }

    @GetMapping("/jobs/{jobId}")
    public ResponseEntity<ApiResponse<IngestionJob>> getJobStatus(@PathVariable UUID jobId) {
        IngestionJob job = ingestionJobRepository.findById(jobId)
                .orElseThrow(() -> new NoSuchElementException("Ingestion job not found"));
        return ResponseEntity.ok(ApiResponse.of(job));
    }

    private void validatePdfUpload(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Uploaded PDF file must not be empty");
        }
        if (file.getSize() > MAX_PDF_BYTES) {
            throw new IllegalArgumentException("PDF file size must not exceed 10 MB");
        }

        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || !originalFilename.trim().toLowerCase(Locale.ROOT).endsWith(".pdf")) {
            throw new IllegalArgumentException("Only .pdf files are supported");
        }

        String normalizedMime = normalizeMimeType(file.getContentType());
        if (!"application/pdf".equals(normalizedMime)) {
            throw new ResponseStatusException(
                    HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                    "Only PDF files (application/pdf) are allowed"
            );
        }

        try (InputStream is = file.getInputStream()) {
            byte[] header = is.readNBytes(PDF_MAGIC_HEADER.length());
            if (header.length < PDF_MAGIC_HEADER.length()
                    || !PDF_MAGIC_HEADER.equals(new String(header, StandardCharsets.US_ASCII))) {
                throw new IllegalArgumentException("Invalid file format: magic bytes do not match PDF");
            }
        } catch (IOException ex) {
            log.warn("Could not read uploaded PDF stream: {}", errorSanitizer.sanitizeException(ex));
            throw new IllegalArgumentException("Could not read uploaded PDF file");
        }
    }

    private static String normalizeMimeType(String rawContentType) {
        if (rawContentType == null) {
            return "";
        }
        int semicolonIndex = rawContentType.indexOf(';');
        String baseType = semicolonIndex >= 0 ? rawContentType.substring(0, semicolonIndex) : rawContentType;
        return baseType.trim().toLowerCase(Locale.ROOT);
    }
}
