package com.studentprep.exam;

import com.studentprep.common.ApiResponse;
import com.studentprep.exam.ExamScriptAuditService.ExamScriptAuditDto;
import com.studentprep.exam.ExamScriptAuditService.GeneratedAuditPdf;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin")
public class AdminExamModerationController {

    private final ExamInternalAPI examInternalAPI;
    private final ExamScriptAuditService examScriptAuditService;

    public AdminExamModerationController(
            ExamInternalAPI examInternalAPI,
            ExamScriptAuditService examScriptAuditService
    ) {
        this.examInternalAPI = examInternalAPI;
        this.examScriptAuditService = examScriptAuditService;
    }

    @GetMapping("/exams/sessions/{sessionId}/script")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPERVISOR')")
    public ResponseEntity<ApiResponse<ExamScriptAuditDto>> getExamSessionScript(@PathVariable UUID sessionId) {
        try {
            return ResponseEntity.ok(ApiResponse.of(examScriptAuditService.getSessionAudit(sessionId)));
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage());
        }
    }

    @GetMapping(value = "/exams/sessions/{sessionId}/script/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPERVISOR')")
    public ResponseEntity<byte[]> getExamSessionScriptPdf(@PathVariable UUID sessionId) {
        try {
            GeneratedAuditPdf auditPdf = examScriptAuditService.generateSessionAuditPdf(sessionId);
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_PDF);
            headers.setContentDispositionFormData("attachment", auditPdf.filename());
            headers.setContentLength(auditPdf.content().length);
            return new ResponseEntity<>(auditPdf.content(), headers, HttpStatus.OK);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage());
        }
    }

    @PostMapping("/exams/sessions/{sessionId}/clear-late")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> clearLateSession(
            @PathVariable UUID sessionId,
            Authentication authentication) {
        String actor = (authentication != null && authentication.getName() != null)
                ? authentication.getName()
                : "ADMIN";

        try {
            examInternalAPI.clearLateSession(sessionId, actor);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ex.getMessage());
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage());
        }

        return ResponseEntity.ok(ApiResponse.of(Map.of(
                "sessionId", sessionId,
                "status", "SUBMITTED",
                "moderationStatus", "CLEARED"
        )));
    }

    @PostMapping("/exams/sessions/{sessionId}/void")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> voidSession(
            @PathVariable UUID sessionId,
            Authentication authentication) {
        String actor = (authentication != null && authentication.getName() != null)
                ? authentication.getName()
                : "ADMIN";

        try {
            examInternalAPI.voidSession(sessionId, actor);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage());
        }

        return ResponseEntity.ok(ApiResponse.of(Map.of(
                "sessionId", sessionId,
                "status", "VOIDED"
        )));
    }

    @PostMapping("/students/{id}/grant-retake")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> grantRetake(
            @PathVariable UUID id,
            Authentication authentication) {
        String actor = (authentication != null && authentication.getName() != null)
                ? authentication.getName()
                : "ADMIN";

        try {
            examInternalAPI.grantRetake(id, actor);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage());
        }

        return ResponseEntity.ok(ApiResponse.of(Map.of(
                "studentId", id,
                "retakeGranted", true
        )));
    }
}
