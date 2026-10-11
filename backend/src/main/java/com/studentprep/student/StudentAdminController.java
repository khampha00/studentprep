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

    private final StudentManagementService studentManagementService;
    private final CsvStudentImporter csvStudentImporter;
    private final RegistrationSlipGenerator registrationSlipGenerator;
    private final StudentRepository studentRepository;

    public StudentAdminController(CsvStudentImporter csvStudentImporter, RegistrationSlipGenerator registrationSlipGenerator, StudentRepository studentRepository, StudentManagementService studentManagementService) {
        this.csvStudentImporter = csvStudentImporter;
        this.registrationSlipGenerator = registrationSlipGenerator;
        this.studentRepository = studentRepository;
        this.studentManagementService = studentManagementService;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<Student>> createStudent(@RequestBody Student student) {
        Student created = studentManagementService.createStudent(student);
        return ResponseEntity.ok(ApiResponse.of(created));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<Student>> updateStudent(@PathVariable UUID id, @RequestBody Student student) {
        Student updated = studentManagementService.updateStudent(id, student);
        return ResponseEntity.ok(ApiResponse.of(updated));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteStudent(@PathVariable UUID id) {
        studentManagementService.deleteStudent(id);
        return ResponseEntity.ok(ApiResponse.of(null));
    }

    @PostMapping("/{id}/reset-pin")
    public ResponseEntity<ApiResponse<Map<String, String>>> resetPin(@PathVariable UUID id) {
        String newPin = studentManagementService.resetPin(id);
        return ResponseEntity.ok(ApiResponse.of(Map.of("newPin", newPin)));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getStudent(@PathVariable UUID id) {
        Student student = studentRepository.findById(id).orElseThrow(() -> new IllegalArgumentException("Not found"));
        return ResponseEntity.ok(ApiResponse.of(Map.of(
            "id", student.getId(),
            "name", student.getName(),
            "registrationNumber", student.getRegistrationNumber(),
            "examCenter", student.getExamCenter() != null ? student.getExamCenter() : "",
            "pin", student.getPin(),
            "subjects", student.getSubjects()
        )));
    }

    @PostMapping("/bulk")
    public ResponseEntity<ApiResponse<Map<String, Object>>> bulkUpload(@RequestParam("csvFile") MultipartFile csvFile) {
        Map<String, Object> result = csvStudentImporter.processBulkCsv(csvFile);
        return ResponseEntity.ok(ApiResponse.of(result));
    }

    @GetMapping(value = "/{id}/slip", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> getRegistrationSlip(@PathVariable UUID id) {
        byte[] pdfBytes = registrationSlipGenerator.getRegistrationSlipPdf(id);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_PDF);
        headers.setContentDispositionFormData("attachment", "slip.pdf");
        return ResponseEntity.ok().headers(headers).body(pdfBytes);
    }

    @GetMapping(value = "/export", produces = "text/csv")
    public ResponseEntity<byte[]> exportStudentsCsv() {
        byte[] csvBytes = csvStudentImporter.exportStudentsCsv();
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
                        "pin", s.getPin(),
                        "examCenter", s.getExamCenter() != null ? s.getExamCenter() : ""
                )).collect(Collectors.toList());
        PagedResponse<List<Map<String, Object>>> pagedResponse = 
            PagedResponse.of(students, studentPage.getNumber(), studentPage.getSize(), studentPage.getTotalElements(), studentPage.getTotalPages());
        return ResponseEntity.ok(pagedResponse);
    }
}
