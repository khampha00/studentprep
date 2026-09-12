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
    
    private static final String REDIS_EXAM_PAYLOAD_KEY = "exam:payload:active";
    private static final int EXAM_DURATION_MINUTES = 120; // 2 hours
    private static final int LATE_SUBMISSION_GRACE_SECONDS = 10;

    public ExamService(ExamSessionRepository sessionRepository, QuestionInternalAPI questionAPI,
                       RedisTemplate<String, Object> redisTemplate, ApplicationEventPublisher eventPublisher,
                       ObjectMapper objectMapper) {
        this.sessionRepository = sessionRepository;
        this.questionAPI = questionAPI;
        this.redisTemplate = redisTemplate;
        this.eventPublisher = eventPublisher;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public ExamStartResponse startExam(UUID userId) {
        ExamPayloadResponse payload = getActivePayload();
        
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
        if (now.isAfter(expectedEndTime.plus(LATE_SUBMISSION_GRACE_SECONDS, ChronoUnit.SECONDS))) {
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
    public ExamPayloadResponse getActivePayload() {
        @SuppressWarnings("unchecked")
        ExamPayloadResponse cached = (ExamPayloadResponse) redisTemplate.opsForValue().get(REDIS_EXAM_PAYLOAD_KEY);
        if (cached != null) {
            return cached;
        }

        ExamPayloadResponse generated = generateStrippedPayload();
        redisTemplate.opsForValue().set(REDIS_EXAM_PAYLOAD_KEY, generated);
        return generated;
    }

    private ExamPayloadResponse generateStrippedPayload() {
        List<Question> questions = questionAPI.getActiveQuestions();
        
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
}
