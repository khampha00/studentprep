package com.studentprep.ingestion;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/admin/ingest")
public class IngestionController {

    @PostMapping("/pdf")
    public ResponseEntity<String> ingestPdf(@RequestParam("file") MultipartFile file) {
        // Architecture Placeholder:
        // Step 1: Forward MultipartFile to Python docling-parser container (http://studentprep-docling:8000/parse)
        // Step 2: Receive Markdown text
        // Step 3: Send Markdown to LLM using LLM_API_KEY
        // Step 4: Save structured JSON to QuestionRepository
        
        return ResponseEntity.ok("PDF ingestion pipeline initiated.");
    }
}
