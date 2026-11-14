package com.studentprep.security;

import com.studentprep.common.ApiResponse;
import com.studentprep.common.SessionRedisKeys;
import com.studentprep.security.dto.UnlockSessionResponse;
import com.studentprep.student.Student;
import com.studentprep.student.StudentInternalAPI;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/students")
public class AdminSessionController {

    private static final Duration UNLOCK_PERMIT_TTL = Duration.ofMinutes(15);
    private static final String UNLOCK_MESSAGE =
            "Active workstation lock cleared. Candidate may re-authenticate once.";

    private final StudentInternalAPI studentInternalAPI;
    private final StringRedisTemplate redisTemplate;
    private final AuditLogRepository auditLogRepository;

    public AdminSessionController(
            StudentInternalAPI studentInternalAPI,
            StringRedisTemplate redisTemplate,
            AuditLogRepository auditLogRepository) {
        this.studentInternalAPI = studentInternalAPI;
        this.redisTemplate = redisTemplate;
        this.auditLogRepository = auditLogRepository;
    }

    @PostMapping("/{id}/unlock-session")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<UnlockSessionResponse>> unlockSession(
            @PathVariable UUID id,
            Authentication authentication) {
        Student student = studentInternalAPI.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Student not found"));

        String canonicalRegNumber = SessionRedisKeys.canonicalIdentifier(student.getRegistrationNumber());
        redisTemplate.delete(SessionRedisKeys.sessionKey(canonicalRegNumber));
        redisTemplate.opsForValue().set(
                SessionRedisKeys.examUnlockKey(canonicalRegNumber),
                "1",
                UNLOCK_PERMIT_TTL);

        String actor = (authentication != null && authentication.getName() != null)
                ? authentication.getName()
                : "ADMIN";
        auditLogRepository.save(AuditLog.builder()
                .username(actor)
                .action("UNLOCK_SESSION:" + canonicalRegNumber)
                .endpoint("/api/v1/admin/students/" + id + "/unlock-session")
                .timestamp(Instant.now())
                .build());

        UnlockSessionResponse response = new UnlockSessionResponse(
                student.getId(),
                student.getRegistrationNumber(),
                true,
                UNLOCK_MESSAGE);
        return ResponseEntity.ok(ApiResponse.of(response));
    }
}
