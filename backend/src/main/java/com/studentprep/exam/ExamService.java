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

    @Transactional(noRollbackFor = org.springframework.web.server.ResponseStatusException.class)
    public ExamStartResponse startExam(UUID userId) {
        // Block students who were previously flagged for malpractice
        if (sessionRepository.existsByUserIdAndStatus(userId, "FLAGGED_TAB_SWITCH")) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.FORBIDDEN,
                    "Your account has been blocked due to exam malpractice. You cannot start an exam."
            );
        }

        Instant now = Instant.now();

        // Check for existing IN_PROGRESS session.
        // If an active session already exists, resume it instead of abandoning it!
        java.util.Optional<ExamSession> existingSession = sessionRepository.findByUserIdAndStatus(userId, "IN_PROGRESS");
        if (existingSession.isPresent()) {
            return resumeExistingSession(existingSession.get(), userId, now);
        }

        ExamPayloadResponse payload = getActivePayload(userId);
        int shuffleSeed = ThreadLocalRandom.current().nextInt(1000, 9999);

        ExamSession session = new ExamSession();
        session.setUserId(userId);
        session.setStartTime(now);
        session.setStatus("IN_PROGRESS");
        session.setShuffleSeed(shuffleSeed);

        Map<String, Object> initialState = new HashMap<>();
        initialState.put("answers", new HashMap<>());
        initialState.put("timeLeft", EXAM_DURATION_MINUTES * 60);
        initialState.put("lastUpdated", System.currentTimeMillis());
        initialState.put("tabSwitchCount", 0);
        session.setStatePayload(initialState);
        try {
            session = sessionRepository.save(session);
        } catch (org.springframework.dao.DataIntegrityViolationException ex) {
            // Concurrent start race condition: resume the session that won the race
            java.util.Optional<ExamSession> concurrent = sessionRepository.findByUserIdAndStatus(userId, "IN_PROGRESS");
            if (concurrent.isPresent()) {
                return resumeExistingSession(concurrent.get(), userId, now);
            }
            throw ex;
        }

        Map<String, Object> payloadMap = new HashMap<>();
        payloadMap.put("questions", payload.getQuestions());
        payloadMap.put("contexts", payload.getContexts());
        payloadMap.put("durationMinutes", payload.getDurationMinutes());
        payloadMap.put("resumed", false);
        payloadMap.put("answers", initialState.get("answers"));
        payloadMap.put("timeLeft", initialState.get("timeLeft"));
        payloadMap.put("tabSwitchCount", 0);

        return new ExamStartResponse(session.getId(), shuffleSeed, payloadMap, initialState, false);
    }

    private ExamStartResponse resumeExistingSession(ExamSession existing, UUID userId, Instant now) {
        Instant expectedEndTime = existing.getStartTime().plus(EXAM_DURATION_MINUTES, ChronoUnit.MINUTES);

        // If time has completely expired including grace period, finalize session
        if (now.isAfter(expectedEndTime.plus(LATE_SUBMISSION_GRACE_SECONDS, ChronoUnit.SECONDS))) {
            finalizeExamSession(existing, "TIME_EXPIRED");
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST,
                    "Your exam duration has expired. Session has been submitted."
            );
        }

        // Resume active session
        long elapsedSeconds = ChronoUnit.SECONDS.between(existing.getStartTime(), now);
        int remainingSeconds = (int) Math.max(0, (EXAM_DURATION_MINUTES * 60) - elapsedSeconds);

        Map<String, Object> statePayload = existing.getStatePayload() != null
                ? new HashMap<>(existing.getStatePayload())
                : new HashMap<>();

        statePayload.putIfAbsent("answers", new HashMap<>());
        statePayload.putIfAbsent("tabSwitchCount", 0);
        statePayload.putIfAbsent("lastUpdated", System.currentTimeMillis());

        if (statePayload.containsKey("timeLeft") && statePayload.get("timeLeft") instanceof Number num) {
            int savedTime = num.intValue();
            statePayload.put("timeLeft", Math.min(savedTime, remainingSeconds));
        } else {
            statePayload.put("timeLeft", remainingSeconds);
        }
        existing.setStatePayload(statePayload);
        sessionRepository.save(existing);

        ExamPayloadResponse payload = getActivePayload(userId);
        Map<String, Object> payloadMap = new HashMap<>();
        payloadMap.put("questions", payload.getQuestions());
        payloadMap.put("contexts", payload.getContexts());
        payloadMap.put("durationMinutes", payload.getDurationMinutes());
        payloadMap.put("resumed", true);
        payloadMap.put("answers", statePayload.get("answers"));
        payloadMap.put("timeLeft", statePayload.get("timeLeft"));
        payloadMap.put("tabSwitchCount", statePayload.get("tabSwitchCount"));

        int shuffleSeed = existing.getShuffleSeed() != null ? existing.getShuffleSeed() : 1234;
        return new ExamStartResponse(existing.getId(), shuffleSeed, payloadMap, statePayload, true);
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

    @Transactional
    public Map<String, Object> getActiveSession(UUID userId) {
        return sessionRepository.findByUserIdAndStatus(userId, "IN_PROGRESS")
            .map(session -> {
                Instant expectedEndTime = session.getStartTime().plus(EXAM_DURATION_MINUTES, ChronoUnit.MINUTES);
                Instant now = Instant.now();
                if (now.isAfter(expectedEndTime.plus(LATE_SUBMISSION_GRACE_SECONDS, ChronoUnit.SECONDS))) {
                    finalizeExamSession(session, "TIME_EXPIRED");
                    return null;
                }

                Map<String, Object> result = new HashMap<>();
                result.put("sessionId", session.getId());
                result.put("status", session.getStatus());
                result.put("startTime", session.getStartTime().toString());
                result.put("shuffleSeed", session.getShuffleSeed());

                long elapsedSeconds = ChronoUnit.SECONDS.between(session.getStartTime(), now);
                int wallClockRemaining = (int) Math.max(0, (EXAM_DURATION_MINUTES * 60) - elapsedSeconds);

                if (session.getStatePayload() != null) {
                    result.put("answers", session.getStatePayload().getOrDefault("answers", new HashMap<>()));
                    Object savedTime = session.getStatePayload().get("timeLeft");
                    if (savedTime instanceof Number num) {
                        result.put("timeLeft", Math.min(num.intValue(), wallClockRemaining));
                    } else {
                        result.put("timeLeft", wallClockRemaining);
                    }
                    result.put("lastSyncedAt", session.getStatePayload().getOrDefault("lastUpdated", System.currentTimeMillis()));
                    result.put("tabSwitchCount", session.getStatePayload().getOrDefault("tabSwitchCount", 0));
                } else {
                    result.put("answers", new HashMap<>());
                    result.put("timeLeft", wallClockRemaining);
                    result.put("lastSyncedAt", System.currentTimeMillis());
                    result.put("tabSwitchCount", 0);
                }
                return result;
            })
            .orElse(null);
    }
}
