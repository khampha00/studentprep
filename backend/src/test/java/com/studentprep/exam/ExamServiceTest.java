package com.studentprep.exam;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.studentprep.exam.dto.ExamPayloadResponse;
import com.studentprep.exam.dto.ExamStartResponse;
import com.studentprep.questionbank.Question;
import com.studentprep.questionbank.QuestionInternalAPI;
import com.studentprep.student.Student;
import com.studentprep.student.StudentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ExamServiceTest {

    @Mock
    private ExamSessionRepository sessionRepository;

    @Mock
    private QuestionInternalAPI questionAPI;

    @Mock
    private RedisTemplate<String, Object> redisTemplate;

    @Mock
    private ValueOperations<String, Object> valueOperations;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private StudentRepository studentRepository;

    private ObjectMapper objectMapper;
    private ExamService examService;

    private final UUID studentId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        examService = new ExamService(sessionRepository, questionAPI, redisTemplate, eventPublisher, objectMapper, studentRepository);

        lenient().when(valueOperations.get("exam:questions:all")).thenReturn(Collections.emptyList());
        lenient().when(studentRepository.findById(studentId)).thenReturn(Optional.of(new Student()));
    }

    @Test
    void startExam_WhenNoActiveSession_CreatesFreshSession() {
        when(sessionRepository.existsByUserIdAndStatus(studentId, "FLAGGED_TAB_SWITCH")).thenReturn(false);
        when(sessionRepository.findByUserIdAndStatus(studentId, "IN_PROGRESS")).thenReturn(Optional.empty());
        when(sessionRepository.save(any(ExamSession.class))).thenAnswer(invocation -> {
            ExamSession s = invocation.getArgument(0);
            s.setId(UUID.randomUUID());
            return s;
        });

        ExamStartResponse response = examService.startExam(studentId);

        assertNotNull(response);
        assertNotNull(response.sessionId());
        assertFalse(response.resumed());
        assertNotNull(response.statePayload());
        assertEquals(7200, response.statePayload().get("timeLeft"));
        assertTrue(((Map<?, ?>) response.statePayload().get("answers")).isEmpty());

        verify(sessionRepository, times(1)).save(any(ExamSession.class));
    }

    @Test
    void startExam_WhenActiveSessionExists_ResumesExistingSessionWithoutWiping() {
        when(sessionRepository.existsByUserIdAndStatus(studentId, "FLAGGED_TAB_SWITCH")).thenReturn(false);

        UUID existingSessionId = UUID.randomUUID();
        ExamSession existingSession = new ExamSession();
        existingSession.setId(existingSessionId);
        existingSession.setUserId(studentId);
        existingSession.setStatus("IN_PROGRESS");
        existingSession.setStartTime(Instant.now().minus(10, ChronoUnit.MINUTES));
        existingSession.setShuffleSeed(7788);

        Map<String, Object> savedState = new HashMap<>();
        savedState.put("answers", Map.of("q1", "optA", "q2", "optB"));
        savedState.put("timeLeft", 6600);
        savedState.put("lastUpdated", System.currentTimeMillis());
        savedState.put("tabSwitchCount", 1);
        existingSession.setStatePayload(savedState);

        when(sessionRepository.findByUserIdAndStatus(studentId, "IN_PROGRESS")).thenReturn(Optional.of(existingSession));
        when(sessionRepository.save(any(ExamSession.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ExamStartResponse response = examService.startExam(studentId);

        assertNotNull(response);
        assertEquals(existingSessionId, response.sessionId(), "Must retain existing session ID");
        assertEquals(7788, response.shuffleSeed(), "Must retain existing shuffle seed");
        assertTrue(response.resumed(), "Must indicate session is resumed");
        assertNotNull(response.statePayload());

        // Answers must be preserved
        @SuppressWarnings("unchecked")
        Map<String, String> answers = (Map<String, String>) response.statePayload().get("answers");
        assertEquals("optA", answers.get("q1"));
        assertEquals("optB", answers.get("q2"));

        // Status must remain IN_PROGRESS
        assertEquals("IN_PROGRESS", existingSession.getStatus());
        assertNull(existingSession.getEndTime(), "End time must remain null for active session");
    }

    @Test
    void startExam_WhenFlaggedForMalpractice_ThrowsForbidden() {
        when(sessionRepository.existsByUserIdAndStatus(studentId, "FLAGGED_TAB_SWITCH")).thenReturn(true);

        ResponseStatusException exception = assertThrows(ResponseStatusException.class, () -> {
            examService.startExam(studentId);
        });

        assertEquals(403, exception.getStatusCode().value());
        verify(sessionRepository, never()).save(any(ExamSession.class));
    }

    @Test
    void startExam_WhenActiveSessionExpired_FinalizesSessionAndThrowsBadRequest() {
        when(sessionRepository.existsByUserIdAndStatus(studentId, "FLAGGED_TAB_SWITCH")).thenReturn(false);

        ExamSession expiredSession = new ExamSession();
        expiredSession.setId(UUID.randomUUID());
        expiredSession.setUserId(studentId);
        expiredSession.setStatus("IN_PROGRESS");
        // Started 125 minutes ago (duration is 120 mins)
        expiredSession.setStartTime(Instant.now().minus(125, ChronoUnit.MINUTES));
        expiredSession.setShuffleSeed(1234);

        when(sessionRepository.findByUserIdAndStatus(studentId, "IN_PROGRESS")).thenReturn(Optional.of(expiredSession));

        ResponseStatusException exception = assertThrows(ResponseStatusException.class, () -> {
            examService.startExam(studentId);
        });

        assertEquals(400, exception.getStatusCode().value());
        assertEquals("LATE_SUBMISSION_FLAGGED", expiredSession.getStatus());
        assertNotNull(expiredSession.getEndTime());
    }

    @Test
    void getActiveSession_ComputesWallClockRemainingTime() {
        ExamSession session = new ExamSession();
        session.setId(UUID.randomUUID());
        session.setUserId(studentId);
        session.setStatus("IN_PROGRESS");
        session.setStartTime(Instant.now().minus(30, ChronoUnit.MINUTES));
        session.setShuffleSeed(5555);

        Map<String, Object> state = new HashMap<>();
        state.put("answers", Map.of("q1", "A"));
        state.put("timeLeft", 7200); // Out-of-date saved timeLeft
        session.setStatePayload(state);

        when(sessionRepository.findByUserIdAndStatus(studentId, "IN_PROGRESS")).thenReturn(Optional.of(session));

        Map<String, Object> result = examService.getActiveSession(studentId);

        assertNotNull(result);
        assertEquals("IN_PROGRESS", result.get("status"));
        int timeLeft = (int) result.get("timeLeft");
        // After 30 minutes, timeLeft must be around 5400 seconds (7200 - 1800), not 7200!
        assertTrue(timeLeft <= 5405 && timeLeft >= 5390, "Time left should reflect elapsed wall clock time");
    }
}
