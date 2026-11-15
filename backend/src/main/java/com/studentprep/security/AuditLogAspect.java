package com.studentprep.security;

import jakarta.servlet.http.HttpServletRequest;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Instant;
import java.util.List;

/**
 * Records administrative audit entries for state-changing operations and sensitive
 * document/script exports while excluding routine read-only polling requests.
 */
@Aspect
@Component
public class AuditLogAspect {

    private static final int MAX_AUDIT_FIELD_LENGTH = 255;
    private static final String ADMIN_URI_PREFIX = "/api/v1/admin/";
    private static final List<String> SENSITIVE_GET_SEGMENTS = List.of(
            "/slip",
            "/pdf",
            "/script",
            "/credentials-pdf",
            "/export"
    );

    private final AuditLogRepository auditLogRepository;

    public AuditLogAspect(AuditLogRepository auditLogRepository) {
        this.auditLogRepository = auditLogRepository;
    }

    @AfterReturning(
            "execution(* com.studentprep..*Controller.*(..)) && (" +
            "@annotation(org.springframework.web.bind.annotation.RequestMapping) || " +
            "@annotation(org.springframework.web.bind.annotation.GetMapping) || " +
            "@annotation(org.springframework.web.bind.annotation.PostMapping) || " +
            "@annotation(org.springframework.web.bind.annotation.PutMapping) || " +
            "@annotation(org.springframework.web.bind.annotation.PatchMapping) || " +
            "@annotation(org.springframework.web.bind.annotation.DeleteMapping))")
    public void logAdminActions(JoinPoint joinPoint) {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)) {
            return;
        }

        HttpServletRequest request = attributes.getRequest();
        String uri = request.getRequestURI();
        if (uri == null || !uri.startsWith(ADMIN_URI_PREFIX)) {
            return;
        }

        String method = request.getMethod();
        if (method == null || "OPTIONS".equalsIgnoreCase(method)) {
            return;
        }
        if (("GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method)) && !isSensitiveGetUri(uri)) {
            return;
        }

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String rawUsername = (authentication != null && authentication.getName() != null
                && !authentication.getName().isBlank())
                ? authentication.getName()
                : "anonymous";

        String rawAction = joinPoint.getSignature().getName();

        AuditLog entry = AuditLog.builder()
                .username(truncate(rawUsername, MAX_AUDIT_FIELD_LENGTH))
                .action(truncate(rawAction, MAX_AUDIT_FIELD_LENGTH))
                .endpoint(truncate(uri, MAX_AUDIT_FIELD_LENGTH))
                .timestamp(Instant.now())
                .build();

        auditLogRepository.save(entry);
    }

    private static boolean isSensitiveGetUri(String uri) {
        for (String segment : SENSITIVE_GET_SEGMENTS) {
            if (uri.endsWith(segment) || uri.contains(segment + "/")) {
                return true;
            }
        }
        return false;
    }

    private static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
