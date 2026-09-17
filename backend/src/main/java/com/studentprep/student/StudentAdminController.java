package com.studentprep.student;

import com.studentprep.common.ApiResponse;
import com.studentprep.common.PagedResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
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

    @GetMapping(value = "/{id}/slip", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> getRegistrationSlip(@PathVariable UUID id) {
        byte[] pdfBytes = studentService.getRegistrationSlipPdf(id);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_PDF);
        headers.setContentDispositionFormData("attachment", "slip.pdf");
        return ResponseEntity.ok().headers(headers).body(pdfBytes);
    }

    @GetMapping(value = "/export", produces = "text/csv")
    public ResponseEntity<byte[]> exportStudentsCsv() {
        byte[] csvBytes = studentService.exportStudentsCsv();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("text/csv"));
        headers.setContentDispositionFormData("attachment", "student_credentials.csv");
        return ResponseEntity.ok().headers(headers).body(csvBytes);
    }

    @GetMapping
    public ResponseEntity<PagedResponse<List<Map<String, Object>>>> getAllStudents(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<Student> studentPage = studentRepository.findAll(PageRequest.of(page, size));
        List<Map<String, Object>> students = studentPage.getContent().stream()
                .map(s -> Map.<String, Object>of(
                        "id", s.getId(),
                        "name", s.getName(),
                        "registrationNumber", s.getRegistrationNumber(),
                        "pin", "12345",
                        "state", s.getState(),
                        "examCenter", s.getExamCenter() != null ? s.getExamCenter() : ""
                )).collect(Collectors.toList());
        PagedResponse<List<Map<String, Object>>> pagedResponse = 
            PagedResponse.of(students, studentPage.getNumber(), studentPage.getSize(), studentPage.getTotalElements(), studentPage.getTotalPages());
        return ResponseEntity.ok(pagedResponse);
    }
}
