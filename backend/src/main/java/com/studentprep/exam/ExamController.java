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
import com.studentprep.student.StudentInternalAPI;

@RestController
@RequestMapping("/api/v1/exams")
public class ExamController {

    private final ExamSessionManager sessionManager;
    private final ExamPayloadGenerator payloadGenerator;
    private final StudentInternalAPI studentInternalAPI;

    public ExamController(ExamSessionManager sessionManager, ExamPayloadGenerator payloadGenerator, StudentInternalAPI studentInternalAPI) {
        this.sessionManager = sessionManager;
        this.payloadGenerator = payloadGenerator;
        this.studentInternalAPI = studentInternalAPI;
    }
    
    private UUID getCurrentStudentId() {
        org.springframework.security.core.Authentication auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        String identifier = auth.getName();
        return studentInternalAPI.findByRegistrationNumberIgnoreCase(identifier)
                .map(com.studentprep.student.Student::getId)
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.UNAUTHORIZED, "Student not found"));
    }
    
    @GetMapping("/active/payload")
    public ResponseEntity<ApiResponse<com.studentprep.exam.dto.ExamPayloadResponse>> getActivePayload() {
        return ResponseEntity.ok(ApiResponse.of(payloadGenerator.getActivePayload(getCurrentStudentId())));
    }
    
    @PostMapping("/start")
    public ResponseEntity<ApiResponse<ExamStartResponse>> startExam() {
        return ResponseEntity.ok(ApiResponse.of(sessionManager.startExam(getCurrentStudentId())));
    }

    @PostMapping("/active/sync")
    public ResponseEntity<ApiResponse<Map<String, String>>> syncExam(
            @RequestParam(required = false) UUID sessionId,
            @Valid @RequestBody ExamSyncRequest request) {
        sessionManager.syncExam(sessionId, getCurrentStudentId(), request);
        return ResponseEntity.ok(ApiResponse.of(Map.of("status", "SYNCED", "serverTime", java.time.Instant.now().toString())));
    }

    @PostMapping("/active/submit")
    public ResponseEntity<ApiResponse<Map<String, String>>> submitExam(
            @RequestParam(required = false) UUID sessionId,
            @RequestParam(required = false, defaultValue = "NORMAL") ExamSessionStatus reason) {
        sessionManager.submitExam(sessionId, getCurrentStudentId(), reason);
        return ResponseEntity.ok(ApiResponse.of(Map.of("status", ExamSessionStatus.SUBMITTED.name(), "serverTime", java.time.Instant.now().toString())));
    }

    @GetMapping("/active/session")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getActiveSession() {
        return ResponseEntity.ok(ApiResponse.of(sessionManager.getActiveSession(getCurrentStudentId())));
    }
}
