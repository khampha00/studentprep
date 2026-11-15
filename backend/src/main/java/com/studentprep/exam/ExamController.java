package com.studentprep.exam;

import com.studentprep.common.ApiResponse;
import com.studentprep.exam.dto.ExamPayloadResponse;
import com.studentprep.exam.dto.ExamStartResponse;
import com.studentprep.exam.dto.ExamSyncRequest;
import com.studentprep.student.Student;
import com.studentprep.student.StudentInternalAPI;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/exams")
public class ExamController {

    private static final Logger log = LoggerFactory.getLogger(ExamController.class);

    private final ExamSessionManager sessionManager;
    private final StudentInternalAPI studentInternalAPI;

    public ExamController(
            ExamSessionManager sessionManager,
            StudentInternalAPI studentInternalAPI
    ) {
        this.sessionManager = sessionManager;
        this.studentInternalAPI = studentInternalAPI;
    }

    private UUID getCurrentStudentId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String identifier = auth.getName();
        return studentInternalAPI.findByRegistrationNumberIgnoreCase(identifier)
                .map(Student::getId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Student not found"));
    }

    @GetMapping("/active/payload")
    public ResponseEntity<ApiResponse<ExamPayloadResponse>> getActivePayload() {
        UUID studentId = getCurrentStudentId();
        if (sessionManager.hasActiveMalpractice(studentId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "MALPRACTICE_BLOCKED");
        }
        ExamPayloadResponse payload = sessionManager.getActivePayload(studentId);
        return ResponseEntity.ok(ApiResponse.of(payload));
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
        return ResponseEntity.ok(ApiResponse.of(Map.of(
                "status", "SYNCED",
                "serverTime", Instant.now().toString()
        )));
    }

    @PostMapping("/active/submit")
    public ResponseEntity<ApiResponse<Map<String, String>>> submitExam(
            @RequestParam(required = false) UUID sessionId,
            @RequestParam(required = false, defaultValue = "NORMAL") ExamSessionStatus reason) {
        ExamSessionStatus finalStatus = sessionManager.submitExam(sessionId, getCurrentStudentId(), reason);
        return ResponseEntity.ok(ApiResponse.of(Map.of(
                "status", finalStatus.name(),
                "serverTime", Instant.now().toString()
        )));
    }

    @GetMapping("/active/session")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getActiveSession() {
        return ResponseEntity.ok(ApiResponse.of(sessionManager.getActiveSession(getCurrentStudentId())));
    }
}
