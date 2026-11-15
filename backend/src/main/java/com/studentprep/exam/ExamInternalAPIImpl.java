package com.studentprep.exam;

import com.studentprep.common.AnswerReceiptHasher;
import com.studentprep.student.Student;
import com.studentprep.student.StudentInternalAPI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class ExamInternalAPIImpl implements ExamInternalAPI {

    private static final Logger log = LoggerFactory.getLogger(ExamInternalAPIImpl.class);

    private final ExamSessionRepository sessionRepository;
    private final StudentInternalAPI studentInternalAPI;
    private final ApplicationEventPublisher eventPublisher;

    public ExamInternalAPIImpl(
            ExamSessionRepository sessionRepository,
            StudentInternalAPI studentInternalAPI,
            ApplicationEventPublisher eventPublisher
    ) {
        this.sessionRepository = sessionRepository;
        this.studentInternalAPI = studentInternalAPI;
        this.eventPublisher = eventPublisher;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean hasFlaggedSession(UUID studentId) {
        if (studentId == null) {
            return false;
        }
        return sessionRepository.existsByUserIdAndStatusAndSupersededFalse(studentId, ExamSessionStatus.FLAGGED_TAB_SWITCH);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean hasActiveSession(UUID studentId) {
        if (studentId == null) {
            return false;
        }
        return sessionRepository.findByUserIdAndStatusAndSupersededFalse(studentId, ExamSessionStatus.IN_PROGRESS).isPresent();
    }

    @Override
    @Transactional(readOnly = true)
    public List<Map<String, Object>> getLiveSessions() {
        List<ExamSession> liveSessions = sessionRepository.findAllByStatus(ExamSessionStatus.IN_PROGRESS).stream()
                .filter(s -> !s.isSuperseded())
                .collect(Collectors.toList());

        Instant now = Instant.now();
        List<Map<String, Object>> result = new ArrayList<>();

        for (ExamSession s : liveSessions) {
            String studentName = studentInternalAPI.findById(s.getUserId())
                    .map(Student::getName)
                    .orElse("Unknown Student");

            int timeLeft = 7200;
            if (s.getExpectedEndTime() != null) {
                long secondsLeft = Duration.between(now, s.getExpectedEndTime()).toSeconds();
                timeLeft = (int) Math.max(0, secondsLeft);
            } else if (s.getStatePayload() != null && s.getStatePayload().get("timeLeft") instanceof Number num) {
                timeLeft = num.intValue();
            }

            result.add(Map.of(
                    "sessionId", s.getId().toString(),
                    "studentName", studentName,
                    "timeLeft", timeLeft,
                    "status", s.getStatus().name()
            ));
        }

        return result;
    }

    @Override
    @Transactional(readOnly = true)
    public long countCompletedExamsBySessionId(UUID examId) {
        if (examId == null) {
            return 0L;
        }
        return sessionRepository.countByIdAndStatusIn(examId, List.of(
                ExamSessionStatus.SUBMITTED,
                ExamSessionStatus.TIME_EXPIRED,
                ExamSessionStatus.LATE_SUBMISSION_FLAGGED,
                ExamSessionStatus.FLAGGED_TAB_SWITCH
        ));
    }

    @Override
    @Transactional(readOnly = true)
    public List<Map<String, Object>> getFlaggedOrLateSessions() {
        List<ExamSession> sessions = sessionRepository.findFlaggedOrLateSessions();
        List<Map<String, Object>> result = new ArrayList<>();

        for (ExamSession s : sessions) {
            Map<String, Object> map = new HashMap<>();
            map.put("sessionId", s.getId().toString());
            map.put("studentId", s.getUserId() != null ? s.getUserId().toString() : null);
            map.put("status", s.getStatus() != null ? s.getStatus().name() : null);
            map.put("startTime", s.getStartTime() != null ? s.getStartTime().toString() : null);
            map.put("endTime", s.getEndTime() != null ? s.getEndTime().toString() : null);

            int tabSwitchCount = 0;
            if (s.getStatePayload() != null && s.getStatePayload().get("tabSwitchCount") instanceof Number num) {
                tabSwitchCount = num.intValue();
            }
            map.put("tabSwitchCount", tabSwitchCount);

            if (s.getUserId() != null) {
                Optional<Student> studentOpt = studentInternalAPI.findById(s.getUserId());
                if (studentOpt.isPresent()) {
                    Student student = studentOpt.get();
                    map.put("studentName", student.getName());
                    map.put("registrationNumber", student.getRegistrationNumber());
                } else {
                    map.put("studentName", "Unknown Student");
                    map.put("registrationNumber", "UNKNOWN");
                }
            } else {
                map.put("studentName", "Unknown Student");
                map.put("registrationNumber", "UNKNOWN");
            }

            result.add(map);
        }

        return result;
    }

    @Override
    @Transactional
    public void clearLateSession(UUID sessionId, String actor) {
        log.info("Admin {} clearing late session {}", actor, sessionId);
        ExamSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Session not found: " + sessionId));

        if (session.getStatus() != ExamSessionStatus.LATE_SUBMISSION_FLAGGED) {
            throw new IllegalStateException("Session is not in LATE_SUBMISSION_FLAGGED status: " + session.getStatus());
        }

        session.setStatus(ExamSessionStatus.SUBMITTED);
        sessionRepository.save(session);

        eventPublisher.publishEvent(new ExamModerationUpdatedEvent(
                sessionId,
                session.getUserId(),
                "CLEAR_LATE",
                actor != null ? actor : "ADMIN",
                Instant.now()
        ));
    }

    @Override
    @Transactional
    public void voidSession(UUID sessionId, String actor) {
        log.info("Admin {} voiding session {}", actor, sessionId);
        ExamSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Session not found: " + sessionId));

        eventPublisher.publishEvent(new ExamModerationUpdatedEvent(
                sessionId,
                session.getUserId(),
                "VOID",
                actor != null ? actor : "ADMIN",
                Instant.now()
        ));
    }

    @Override
    @Transactional
    public void grantRetake(UUID studentId, String actor) {
        log.info("Admin {} granting retake to student {}", actor, studentId);
        List<ExamSession> sessions = sessionRepository.findAllByUserIdAndSupersededFalse(studentId);
        for (ExamSession session : sessions) {
            session.setSuperseded(true);
            sessionRepository.save(session);
        }

        eventPublisher.publishEvent(new ExamModerationUpdatedEvent(
                null,
                studentId,
                "GRANT_RETAKE",
                actor != null ? actor : "ADMIN",
                Instant.now()
        ));
    }

    @Override
    @Transactional(readOnly = true)
    public String getSessionAnswerReceiptCode(UUID sessionId) {
        if (sessionId == null) {
            return null;
        }
        return sessionRepository.findById(sessionId)
                .map(session -> {
                    Map<String, Object> state = session.getStatePayload();
                    Map<String, String> answers = extractAnswersMap(state);
                    return AnswerReceiptHasher.computeReceiptCode(sessionId, answers);
                })
                .orElse(null);
    }

    private static Map<String, String> extractAnswersMap(Map<String, Object> statePayload) {
        if (statePayload == null) {
            return Collections.emptyMap();
        }
        Object ansObj = statePayload.get("answers");
        if (!(ansObj instanceof Map<?, ?> ansMap)) {
            return Collections.emptyMap();
        }
        Map<String, String> answers = new HashMap<>();
        for (Map.Entry<?, ?> entry : ansMap.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                answers.put(entry.getKey().toString(), entry.getValue().toString());
            }
        }
        return answers;
    }
}
