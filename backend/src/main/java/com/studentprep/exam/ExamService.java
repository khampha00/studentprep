package com.studentprep.exam;

import com.studentprep.exam.dto.ExamStartResponse;
import com.studentprep.exam.dto.ExamSyncRequest;
import com.studentprep.exam.dto.ExamPayloadResponse;
import com.studentprep.questionbank.QuestionInternalAPI;
import com.studentprep.questionbank.Question;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.List;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

@Service
public class ExamService {

    private final ExamSessionRepository sessionRepository;
    private final QuestionInternalAPI questionAPI;
    private final RedisTemplate<String, Object> redisTemplate;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;
    private final com.studentprep.student.StudentRepository studentRepository;
    
    private static final String REDIS_EXAM_PAYLOAD_KEY = "exam:payload:active";
    private static final int EXAM_DURATION_MINUTES = 120; // 2 hours
    private static final int LATE_SUBMISSION_GRACE_SECONDS = 10;

    public ExamService(ExamSessionRepository sessionRepository, QuestionInternalAPI questionAPI,
                       RedisTemplate<String, Object> redisTemplate, ApplicationEventPublisher eventPublisher,
                       ObjectMapper objectMapper, com.studentprep.student.StudentRepository studentRepository) {
        this.sessionRepository = sessionRepository;
        this.questionAPI = questionAPI;
        this.redisTemplate = redisTemplate;
        this.eventPublisher = eventPublisher;
        this.objectMapper = objectMapper;
        this.studentRepository = studentRepository;
    }

    @Transactional
    public ExamStartResponse startExam(UUID userId) {
        // Block students who were previously flagged for malpractice
        if (sessionRepository.existsByUserIdAndStatus(userId, "FLAGGED_TAB_SWITCH")) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.FORBIDDEN,
                    "Your account has been blocked due to exam malpractice. You cannot start an exam."
            );
        }

        // Bug fix: Check for existing IN_PROGRESS session to prevent duplicate key violation
        // on idx_unique_active_session partial unique index
        java.util.Optional<ExamSession> existingSession = sessionRepository.findByUserIdAndStatus(userId, "IN_PROGRESS");
        if (existingSession.isPresent()) {
            ExamSession existing = existingSession.get();
            // Close the stale session (e.g. from a tab-switch violation that didn't clean up)
            existing.setStatus("SUBMITTED");
            existing.setEndTime(Instant.now());
            if (existing.getStatePayload() == null) {
                existing.setStatePayload(new HashMap<>());
            }
            existing.getStatePayload().put("autoClosedReason", "NEW_SESSION_REQUESTED");
            sessionRepository.saveAndFlush(existing);
        }

        ExamPayloadResponse payload = getActivePayload(userId);
        
        int shuffleSeed = ThreadLocalRandom.current().nextInt(1000, 9999);

        ExamSession session = new ExamSession();
        session.setUserId(userId);
        session.setStartTime(Instant.now());
        session.setStatus("IN_PROGRESS");
        session.setShuffleSeed(shuffleSeed);
        session = sessionRepository.save(session);

        Map<String, Object> payloadMap = new HashMap<>();
        payloadMap.put("questions", payload.getQuestions());
        payloadMap.put("contexts", payload.getContexts());
        payloadMap.put("durationMinutes", payload.getDurationMinutes());
        
        return new ExamStartResponse(session.getId(), shuffleSeed, payloadMap);
    }

    private ExamSession resolveSession(UUID sessionId, UUID studentId) {
        if (sessionId != null) {
            java.util.Optional<ExamSession> sessionOpt = sessionRepository.findById(sessionId);
            if (sessionOpt.isPresent()) {
                ExamSession s = sessionOpt.get();
                if (!s.getUserId().equals(studentId)) {
                    throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN, "Access Denied");
                }
                return s;
            }
        }
        // Fallback: look up active session for this student
        return sessionRepository.findByUserIdAndStatus(studentId, "IN_PROGRESS")
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "No active exam session found"));
    }

    @Transactional
    public void syncExam(UUID sessionId, UUID studentId, ExamSyncRequest request) {
        ExamSession session = resolveSession(sessionId, studentId);
        if ("SUBMITTED".equals(session.getStatus()) || "FLAGGED_TAB_SWITCH".equals(session.getStatus())) {
            return; // Silently accept — session was already finalized
        }
        session.setStatePayload(request.statePayload());
        
        if (Boolean.TRUE.equals(request.statePayload().get("isFinal"))) {
            String reason = (String) request.statePayload().getOrDefault("reason", "NORMAL");
            finalizeExamSession(session, reason);
            return;
        }
        
        sessionRepository.save(session);
    }

    @Transactional
    public void submitExam(UUID sessionId, UUID studentId) {
        submitExam(sessionId, studentId, "NORMAL");
    }

    @Transactional
    public void submitExam(UUID sessionId, UUID studentId, String reasonParam) {
        ExamSession session = resolveSession(sessionId, studentId);
        if ("SUBMITTED".equals(session.getStatus()) || "FLAGGED_TAB_SWITCH".equals(session.getStatus())) {
            return; // Already finalized — idempotent
        }

        String reason = reasonParam;
        if (reason == null || "NORMAL".equals(reason)) {
            reason = session.getStatePayload() != null ? (String) session.getStatePayload().getOrDefault("reason", "NORMAL") : "NORMAL";
        }
        
        finalizeExamSession(session, reason);
    }

    private void finalizeExamSession(ExamSession session, String reason) {
        if ("SUBMITTED".equals(session.getStatus()) || "FLAGGED_TAB_SWITCH".equals(session.getStatus())) {
            return;
        }

        Instant expectedEndTime = session.getStartTime().plus(EXAM_DURATION_MINUTES, ChronoUnit.MINUTES);
        Instant now = Instant.now();
        
        if ("FLAGGED_TAB_SWITCH".equals(reason)) {
            session.setStatus("FLAGGED_TAB_SWITCH");
        } else if (now.isAfter(expectedEndTime.plus(LATE_SUBMISSION_GRACE_SECONDS, ChronoUnit.SECONDS))) {
            session.setStatus("LATE_SUBMISSION_FLAGGED");
        } else {
            session.setStatus("SUBMITTED");
        }
        
        session.setEndTime(now);
        sessionRepository.saveAndFlush(session);

        // Transactional Outbox Pattern
        eventPublisher.publishEvent(new ExamSubmittedEvent(session.getId(), session.getUserId(), session.getStatePayload()));
    }

    @Transactional(readOnly = true)
    public ExamPayloadResponse getActivePayload(UUID userId) {
        @SuppressWarnings("unchecked")
        List<Question> cachedQuestions = (List<Question>) redisTemplate.opsForValue().get("exam:questions:all");
        if (cachedQuestions == null) {
            cachedQuestions = questionAPI.getActiveQuestions();
            redisTemplate.opsForValue().set("exam:questions:all", cachedQuestions);
        }

        com.studentprep.student.Student student = studentRepository.findById(userId).orElse(null);
        List<String> enrolledSubjectIds = new ArrayList<>();
        if (student != null) {
            for (com.studentprep.questionbank.Subject subject : student.getSubjects()) {
                enrolledSubjectIds.add(subject.getId().toString());
            }
        }

        List<Question> filteredQuestions = new ArrayList<>();
        for (Question q : cachedQuestions) {
            if (student == null || q.getSubject() == null) {
                filteredQuestions.add(q);
            } else if (enrolledSubjectIds.contains(q.getSubject().getId().toString())) {
                filteredQuestions.add(q);
            }
        }

        return generateStrippedPayload(filteredQuestions);
    }

    private ExamPayloadResponse generateStrippedPayload(List<Question> questions) {
        List<Object> strippedQuestions = new ArrayList<>();
        Map<String, String> contextsMap = new HashMap<>();
        
        Map<String, List<Object>> groupedQuestions = new HashMap<>();
        List<Object> standaloneQuestions = new ArrayList<>();

        long hashSeed = 0;
        for (Question q : questions) {
            hashSeed += q.getId().hashCode();
            Map<String, Object> map = this.objectMapper.convertValue(q, new TypeReference<Map<String, Object>>() {});
            Map<String, Object> content = (Map<String, Object>) map.get("content");
            if (content != null) {
                content.remove("correctOption");
            }
            if (q.getContext() != null) {
                String ctxId = q.getContext().getId().toString();
                map.put("contextId", ctxId);
                contextsMap.put(ctxId, q.getContext().getPassage());
                groupedQuestions.computeIfAbsent(ctxId, k -> new ArrayList<>()).add(map);
            } else {
                standaloneQuestions.add(map);
            }
        }
        
        List<List<Object>> allGroups = new ArrayList<>();
        allGroups.addAll(groupedQuestions.values());
        for (Object sq : standaloneQuestions) {
            allGroups.add(Collections.singletonList(sq));
        }
        
        long seed = hashSeed == 0 ? 12345L : hashSeed;
        Collections.shuffle(allGroups, new Random(seed));
        
        for (List<Object> group : allGroups) {
            strippedQuestions.addAll(group);
        }
        
        ExamPayloadResponse response = new ExamPayloadResponse();
        response.setExamId(UUID.randomUUID());
        response.setShuffleSeed(seed);
        response.setDurationMinutes(EXAM_DURATION_MINUTES);
        response.setQuestions(strippedQuestions);
        response.setContexts(contextsMap);
        return response;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getActiveSession(UUID userId) {
        return sessionRepository.findByUserIdAndStatus(userId, "IN_PROGRESS")
            .map(session -> {
                Map<String, Object> result = new HashMap<>();
                result.put("sessionId", session.getId());
                result.put("status", session.getStatus());
                result.put("startTime", session.getStartTime().toString());
                result.put("shuffleSeed", session.getShuffleSeed());
                if (session.getStatePayload() != null) {
                    result.put("answers", session.getStatePayload().get("answers"));
                    result.put("timeLeft", session.getStatePayload().get("timeLeft"));
                    result.put("lastSyncedAt", session.getStatePayload().get("lastUpdated"));
                    result.put("tabSwitchCount", session.getStatePayload().get("tabSwitchCount"));
                }
                return result;
            })
            .orElse(null);
    }
}
