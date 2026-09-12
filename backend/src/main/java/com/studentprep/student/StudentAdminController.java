package com.studentprep.student;

import com.studentprep.common.ApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/admin/students")
public class StudentAdminController {

    private final StudentService studentService;
    private final StudentRepository studentRepository;

    public StudentAdminController(StudentService studentService, StudentRepository studentRepository) {
        this.studentService = studentService;
        this.studentRepository = studentRepository;
    }

    @PostMapping("/bulk")
    public ResponseEntity<ApiResponse<Map<String, Object>>> bulkUpload(@RequestParam("csvFile") MultipartFile csvFile) {
        Map<String, Object> result = studentService.processBulkCsv(csvFile);
        return ResponseEntity.ok(ApiResponse.of(result));
    }

    @GetMapping(value = "/{id}/slip", produces = org.springframework.http.MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> getRegistrationSlip(@PathVariable UUID id) {
        byte[] pdfBytes = studentService.getRegistrationSlipPdf(id);
        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_PDF);
        headers.setContentDispositionFormData("attachment", "slip.pdf");
        return ResponseEntity.ok().headers(headers).body(pdfBytes);
    }

    @GetMapping
    public ResponseEntity<ApiResponse<java.util.List<Map<String, Object>>>> getAllStudents() {
        java.util.List<Map<String, Object>> students = studentRepository.findAll().stream()
                .map(s -> Map.<String, Object>of(
                        "id", s.getId(),
                        "name", s.getName(),
                        "registrationNumber", s.getRegistrationNumber(),
                        "state", s.getState(),
                        "examCenter", s.getExamCenter() != null ? s.getExamCenter() : ""
                )).collect(Collectors.toList());
        return ResponseEntity.ok(ApiResponse.of(students));
    }
}
