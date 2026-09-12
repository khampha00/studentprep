package com.studentprep.exam;

import com.studentprep.exam.dto.ExamStartResponse;
import com.studentprep.exam.dto.ExamSyncRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import jakarta.validation.Valid;
import java.util.UUID;
import java.util.Map;
import java.time.Instant;
import com.studentprep.common.ApiResponse;

@RestController
@RequestMapping("/api/v1/exams")
public class ExamController {

    private final ExamService examService;
    private final com.studentprep.student.StudentRepository studentRepository;

    public ExamController(ExamService examService, com.studentprep.student.StudentRepository studentRepository) {
        this.examService = examService;
        this.studentRepository = studentRepository;
    }
    
    private UUID getCurrentStudentId() {
        org.springframework.security.core.Authentication auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        String identifier = auth.getName();
        return studentRepository.findByRegistrationNumber(identifier)
                .map(com.studentprep.student.Student::getId)
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.UNAUTHORIZED, "Student not found"));
    }
    
    @GetMapping("/active/payload")
    public ResponseEntity<ApiResponse<com.studentprep.exam.dto.ExamPayloadResponse>> getActivePayload() {
        return ResponseEntity.ok(ApiResponse.of(examService.getActivePayload(getCurrentStudentId())));
    }
    
    @PostMapping("/start")
    public ResponseEntity<ApiResponse<ExamStartResponse>> startExam() {
        return ResponseEntity.ok(ApiResponse.of(examService.startExam(getCurrentStudentId())));
    }

    @PostMapping("/active/sync")
    public ResponseEntity<ApiResponse<Map<String, String>>> syncExam(@RequestParam UUID sessionId, @Valid @RequestBody ExamSyncRequest request) {
        examService.syncExam(sessionId, getCurrentStudentId(), request);
        return ResponseEntity.ok(ApiResponse.of(Map.of("status", "SYNCED", "serverTime", Instant.now().toString())));
    }

    @PostMapping("/active/submit")
    public ResponseEntity<ApiResponse<Map<String, String>>> submitExam(@RequestParam UUID sessionId) {
        examService.submitExam(sessionId, getCurrentStudentId());
        return ResponseEntity.ok(ApiResponse.of(Map.of("status", "SUBMITTED", "serverTime", Instant.now().toString())));
    }

    @GetMapping("/active/session")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getActiveSession() {
        return ResponseEntity.ok(ApiResponse.of(examService.getActiveSession(getCurrentStudentId())));
    }
}
