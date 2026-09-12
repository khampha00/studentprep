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

@Aspect
@Component
public class AuditLogAspect {

    private final AuditLogRepository auditLogRepository;

    public AuditLogAspect(AuditLogRepository auditLogRepository) {
        this.auditLogRepository = auditLogRepository;
    }

    @AfterReturning("execution(* com.studentprep..*Controller.*(..)) && @annotation(org.springframework.web.bind.annotation.RequestMapping) || " +
                    "execution(* com.studentprep..*Controller.*(..)) && @annotation(org.springframework.web.bind.annotation.GetMapping) || " +
                    "execution(* com.studentprep..*Controller.*(..)) && @annotation(org.springframework.web.bind.annotation.PostMapping) || " +
                    "execution(* com.studentprep..*Controller.*(..)) && @annotation(org.springframework.web.bind.annotation.PutMapping) || " +
                    "execution(* com.studentprep..*Controller.*(..)) && @annotation(org.springframework.web.bind.annotation.DeleteMapping)")
    public void logAdminActions(JoinPoint joinPoint) {
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes == null) return;
        
        HttpServletRequest request = attributes.getRequest();
        String uri = request.getRequestURI();
        
        // Only log /api/v1/admin/** endpoints
        if (uri != null && uri.startsWith("/api/v1/admin/")) {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            String username = (authentication != null && authentication.getName() != null) 
                                ? authentication.getName() 
                                : "anonymous";
                                
            String action = joinPoint.getSignature().getName();
            
            AuditLog log = AuditLog.builder()
                .username(username)
                .action(action)
                .endpoint(uri)
                .timestamp(Instant.now())
                .build();
                
            auditLogRepository.save(log);
        }
    }
}
