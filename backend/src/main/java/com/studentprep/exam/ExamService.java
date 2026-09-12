package com.studentprep.exam;

import com.studentprep.exam.dto.ExamStartResponse;
import com.studentprep.exam.dto.ExamSyncRequest;
import com.studentprep.questionbank.QuestionInternalAPI;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

@Service
public class ExamService {

    private final ExamSessionRepository sessionRepository;
    private final QuestionInternalAPI questionAPI;
    private final RedisTemplate<String, Object> redisTemplate;
    private final ApplicationEventPublisher eventPublisher;
    
    private static final String REDIS_EXAM_PAYLOAD_KEY = "exam:payload:active";
    private static final int EXAM_DURATION_MINUTES = 120; // 2 hours

    public ExamService(ExamSessionRepository sessionRepository, QuestionInternalAPI questionAPI,
                       RedisTemplate<String, Object> redisTemplate, ApplicationEventPublisher eventPublisher) {
        this.sessionRepository = sessionRepository;
        this.questionAPI = questionAPI;
        this.redisTemplate = redisTemplate;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public ExamStartResponse startExam(UUID userId) {
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) redisTemplate.opsForValue().get(REDIS_EXAM_PAYLOAD_KEY);
        
        if (payload == null) {
            payload = new HashMap<>();
            payload.put("questions", questionAPI.getActiveQuestions());
            redisTemplate.opsForValue().set(REDIS_EXAM_PAYLOAD_KEY, payload);
        }

        int shuffleSeed = ThreadLocalRandom.current().nextInt(1000, 9999);

        ExamSession session = new ExamSession();
        session.setUserId(userId);
        session.setStartTime(Instant.now());
        session.setStatus("IN_PROGRESS");
        session.setShuffleSeed(shuffleSeed);
        session = sessionRepository.save(session);

        return new ExamStartResponse(session.getId(), shuffleSeed, payload);
    }

    @Transactional
    public void syncExam(UUID sessionId, ExamSyncRequest request) {
        ExamSession session = sessionRepository.findById(sessionId).orElseThrow();
        if ("SUBMITTED".equals(session.getStatus())) {
            throw new IllegalStateException("Cannot sync a submitted exam.");
        }
        session.setStatePayload(request.statePayload());
        
        if (Boolean.TRUE.equals(request.statePayload().get("isFinal"))) {
            session.setStatus("SUBMITTED");
            session.setEndTime(Instant.now());
        }
        
        sessionRepository.save(session);
    }

    @Transactional
    public void submitExam(UUID sessionId) {
        ExamSession session = sessionRepository.findById(sessionId).orElseThrow();
        
        if ("SUBMITTED".equals(session.getStatus())) {
            throw new IllegalStateException("Exam is already submitted.");
        }

        Instant expectedEndTime = session.getStartTime().plus(EXAM_DURATION_MINUTES, ChronoUnit.MINUTES);
        Instant now = Instant.now();
        
        // FSD 4.2: Validate against Server Time (10-second grace period)
        if (now.isAfter(expectedEndTime.plus(10, ChronoUnit.SECONDS))) {
            session.setStatus("LATE_SUBMISSION_FLAGGED");
        } else {
            session.setStatus("SUBMITTED");
        }
        
        session.setEndTime(now);
        sessionRepository.save(session);

        // Transactional Outbox Pattern
        eventPublisher.publishEvent(new ExamSubmittedEvent(session.getId(), session.getUserId(), session.getStatePayload()));
    }

    @Transactional(readOnly = true)
    public com.studentprep.exam.dto.ExamPayloadResponse getActivePayload() {
        java.util.List<com.studentprep.questionbank.Question> questions = questionAPI.getActiveQuestions();
        
        java.util.List<Object> strippedQuestions = new java.util.ArrayList<>();
        Map<String, String> contextsMap = new java.util.HashMap<>();
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        mapper.registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
        
        Map<String, java.util.List<Object>> groupedQuestions = new java.util.HashMap<>();
        java.util.List<Object> standaloneQuestions = new java.util.ArrayList<>();

        long hashSeed = 0;
        for (com.studentprep.questionbank.Question q : questions) {
            hashSeed += q.getId().hashCode();
            Map<String, Object> map = mapper.convertValue(q, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
            Map<String, Object> content = (Map<String, Object>) map.get("content");
            if (content != null) {
                content.remove("correctOption");
            }
            if (q.getContext() != null) {
                String ctxId = q.getContext().getId().toString();
                map.put("contextId", ctxId);
                contextsMap.put(ctxId, q.getContext().getPassage());
                groupedQuestions.computeIfAbsent(ctxId, k -> new java.util.ArrayList<>()).add(map);
            } else {
                standaloneQuestions.add(map);
            }
        }
        
        java.util.List<java.util.List<Object>> allGroups = new java.util.ArrayList<>();
        allGroups.addAll(groupedQuestions.values());
        for (Object sq : standaloneQuestions) {
            allGroups.add(java.util.Collections.singletonList(sq));
        }
        
        long seed = hashSeed == 0 ? 12345L : hashSeed;
        java.util.Collections.shuffle(allGroups, new java.util.Random(seed));
        
        for (java.util.List<Object> group : allGroups) {
            strippedQuestions.addAll(group);
        }
        
        com.studentprep.exam.dto.ExamPayloadResponse response = new com.studentprep.exam.dto.ExamPayloadResponse();
        response.setExamId(UUID.randomUUID());
        response.setShuffleSeed(seed);
        response.setDurationMinutes(EXAM_DURATION_MINUTES);
        response.setQuestions(strippedQuestions);
        response.setContexts(contextsMap);
        return response;
    }
}
