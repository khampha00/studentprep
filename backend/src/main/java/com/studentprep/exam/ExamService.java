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
}
