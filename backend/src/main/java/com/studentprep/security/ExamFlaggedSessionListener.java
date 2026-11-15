package com.studentprep.security;

import com.studentprep.common.SessionRedisKeys;
import com.studentprep.exam.ExamSessionFlaggedEvent;
import com.studentprep.student.Student;
import com.studentprep.student.StudentInternalAPI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Optional;

@Component
public class ExamFlaggedSessionListener {

    private static final Logger log = LoggerFactory.getLogger(ExamFlaggedSessionListener.class);

    private final StudentInternalAPI studentInternalAPI;
    private final StringRedisTemplate redisTemplate;

    public ExamFlaggedSessionListener(StudentInternalAPI studentInternalAPI, StringRedisTemplate redisTemplate) {
        this.studentInternalAPI = studentInternalAPI;
        this.redisTemplate = redisTemplate;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onExamSessionFlagged(ExamSessionFlaggedEvent event) {
        if (event == null || event.userId() == null) {
            return;
        }

        log.info("Processing ExamSessionFlaggedEvent for session={}, user={}, reason={}",
                event.sessionId(), event.userId(), event.reason());

        Optional<Student> studentOpt = studentInternalAPI.findById(event.userId());
        if (studentOpt.isEmpty()) {
            log.warn("Student not found for userId: {}", event.userId());
            return;
        }

        String regNumber = studentOpt.get().getRegistrationNumber();
        if (regNumber == null || regNumber.isBlank()) {
            log.warn("Student {} has empty registration number", event.userId());
            return;
        }

        String canonicalId = SessionRedisKeys.canonicalIdentifier(regNumber);
        String sessionKey = SessionRedisKeys.sessionKey(canonicalId);

        try {
            Boolean deleted = redisTemplate.delete(sessionKey);
            log.info("Revoked active Redis session key {}: result={}", sessionKey, deleted);
        } catch (DataAccessException ex) {
            log.warn("Redis unavailable while revoking session key {}: {}", sessionKey, ex.getMessage());
        }
    }
}
