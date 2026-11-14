package com.studentprep.student;

import com.studentprep.common.ApiResponse;
import com.studentprep.common.PagedResponse;
import com.studentprep.common.SessionRedisKeys;
import com.studentprep.student.StudentManagementService.ProvisionedStudent;
import com.studentprep.student.dto.BulkImportResponse;
import com.studentprep.student.dto.CreateStudentRequest;
import com.studentprep.student.dto.CreatedStudentResponse;
import com.studentprep.student.dto.ResetPinResponse;
import com.studentprep.student.dto.StudentResponse;
import com.studentprep.student.dto.SubjectSummaryResponse;
import com.studentprep.student.dto.UpdateStudentRequest;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/students")
public class StudentAdminController {

    private static final Logger log = LoggerFactory.getLogger(StudentAdminController.class);
    private static final int MAX_PAGE_SIZE = 200;

    private final StudentManagementService studentManagementService;
    private final CsvStudentImporter csvStudentImporter;
    private final RegistrationSlipGenerator registrationSlipGenerator;
    private final StudentRepository studentRepository;
    private final StringRedisTemplate redisTemplate;

    public StudentAdminController(
            CsvStudentImporter csvStudentImporter,
            RegistrationSlipGenerator registrationSlipGenerator,
            StudentRepository studentRepository,
            StudentManagementService studentManagementService,
            StringRedisTemplate redisTemplate) {
        this.csvStudentImporter = csvStudentImporter;
        this.registrationSlipGenerator = registrationSlipGenerator;
        this.studentRepository = studentRepository;
        this.studentManagementService = studentManagementService;
        this.redisTemplate = redisTemplate;
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<CreatedStudentResponse>> createStudent(
            @Valid @RequestBody CreateStudentRequest request) {
        ProvisionedStudent provisioned = studentManagementService.createStudent(request);
        Student student = provisioned.student();
        CreatedStudentResponse response = new CreatedStudentResponse(
                student.getId(),
                student.getName(),
                student.getRegistrationNumber(),
                student.getExamCenter() != null ? student.getExamCenter() : "",
                toSubjectSummaries(student),
                provisioned.oneTimePin());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<StudentResponse>> updateStudent(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateStudentRequest request) {
        Student updated = studentManagementService.updateStudent(id, request);
        return ResponseEntity.ok(ApiResponse.of(toStudentResponse(updated, hasActiveSession(updated))));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<Void>> deleteStudent(@PathVariable UUID id) {
        studentManagementService.deleteStudent(id);
        return ResponseEntity.ok(ApiResponse.of(null));
    }

    @PostMapping("/{id}/reset-pin")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<ResetPinResponse>> resetPin(@PathVariable UUID id) {
        ResetPinResponse resetResponse = studentManagementService.resetPin(id);
        return ResponseEntity.ok(ApiResponse.of(resetResponse));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPERVISOR')")
    public ResponseEntity<ApiResponse<StudentResponse>> getStudent(@PathVariable UUID id) {
        Student student = studentRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Student not found"));
        return ResponseEntity.ok(ApiResponse.of(toStudentResponse(student, hasActiveSession(student))));
    }

    @PostMapping("/bulk")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<BulkImportResponse>> bulkUpload(
            @RequestParam("csvFile") MultipartFile csvFile) {
        BulkImportResponse result = csvStudentImporter.processBulkCsv(csvFile);
        return ResponseEntity.ok(ApiResponse.of(result));
    }

    @GetMapping(value = "/{id}/slip", produces = MediaType.APPLICATION_PDF_VALUE)
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<byte[]> getRegistrationSlip(@PathVariable UUID id) {
        byte[] pdfBytes = registrationSlipGenerator.getRegistrationSlipPdf(id);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_PDF);
        headers.setContentDispositionFormData("attachment", "slip.pdf");
        return ResponseEntity.ok().headers(headers).body(pdfBytes);
    }

    @GetMapping(value = "/export", produces = "text/csv")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<byte[]> exportStudentsCsv() {
        byte[] csvBytes = csvStudentImporter.exportStudentsCsv();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("text/csv"));
        headers.setContentDispositionFormData("attachment", "student_roster.csv");
        return ResponseEntity.ok().headers(headers).body(csvBytes);
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPERVISOR')")
    public ResponseEntity<PagedResponse<List<StudentResponse>>> getAllStudents(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("Page must be >= 0 and size must be between 1 and " + MAX_PAGE_SIZE);
        }
        Page<Student> studentPage = studentRepository.findAll(PageRequest.of(page, size));
        List<Student> content = studentPage.getContent();
        List<Boolean> activeFlags = fetchActiveSessionFlags(content);

        List<StudentResponse> students = new ArrayList<>(content.size());
        for (int i = 0; i < content.size(); i++) {
            students.add(toStudentResponse(content.get(i), activeFlags.get(i)));
        }

        PagedResponse<List<StudentResponse>> pagedResponse = PagedResponse.of(
                students,
                studentPage.getNumber(),
                studentPage.getSize(),
                studentPage.getTotalElements(),
                studentPage.getTotalPages());
        return ResponseEntity.ok(pagedResponse);
    }

    private StudentResponse toStudentResponse(Student student, boolean activeSession) {
        return new StudentResponse(
                student.getId(),
                student.getName(),
                student.getRegistrationNumber(),
                student.getExamCenter() != null ? student.getExamCenter() : "",
                student.isPinResetRequired(),
                activeSession,
                toSubjectSummaries(student));
    }

    private List<SubjectSummaryResponse> toSubjectSummaries(Student student) {
        return student.getSubjects().stream()
                .map(subject -> new SubjectSummaryResponse(subject.getId(), subject.getName()))
                .sorted(Comparator.comparing(SubjectSummaryResponse::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    private boolean hasActiveSession(Student student) {
        if (redisTemplate == null || student.getRegistrationNumber() == null) {
            return false;
        }
        try {
            String value = redisTemplate.opsForValue()
                    .get(SessionRedisKeys.sessionKey(student.getRegistrationNumber()));
            return value != null && !value.isBlank();
        } catch (DataAccessException ex) {
            log.warn("Failed to read Redis active session state", ex);
            return false;
        }
    }

    private List<Boolean> fetchActiveSessionFlags(List<Student> students) {
        if (students.isEmpty() || redisTemplate == null) {
            return Collections.nCopies(students.size(), false);
        }
        List<String> keys = students.stream()
                .map(student -> SessionRedisKeys.sessionKey(student.getRegistrationNumber()))
                .toList();
        try {
            List<String> values = redisTemplate.opsForValue().multiGet(keys);
            if (values == null || values.size() != students.size()) {
                return Collections.nCopies(students.size(), false);
            }
            return values.stream()
                    .map(value -> value != null && !value.isBlank())
                    .toList();
        } catch (DataAccessException ex) {
            log.warn("Failed to read Redis active session states for student page", ex);
            return Collections.nCopies(students.size(), false);
        }
    }
}
