package com.studentprep.analytics;

import com.studentprep.common.OutboxRecoveryScheduler;
import com.studentprep.common.PagedResponse;
import com.studentprep.common.SessionRedisKeys;
import com.studentprep.exam.ExamCacheService;
import com.studentprep.exam.ExamSession;
import com.studentprep.exam.ExamSessionRepository;
import com.studentprep.exam.ExamSessionStatus;
import com.studentprep.questionbank.Question;
import com.studentprep.questionbank.QuestionInternalAPI;
import com.studentprep.questionbank.QuestionRepository;
import com.studentprep.questionbank.QuestionService;
import com.studentprep.questionbank.QuestionStatus;
import com.studentprep.security.JwtUtil;
import com.studentprep.student.Student;
import com.studentprep.student.StudentRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.modulith.events.IncompleteEventPublications;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class OutboxAndRedisResilienceIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ExamResultRepository examResultRepository;

    @Autowired
    private ExamSessionRepository examSessionRepository;

    @Autowired
    private StudentRepository studentRepository;

    @Autowired
    private LeaderboardService leaderboardService;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private AnalyticsController analyticsController;

    @Autowired
    private JwtUtil jwtUtil;

    private final List<UUID> createdResultIds = new ArrayList<>();
    private final List<UUID> createdSessionIds = new ArrayList<>();
    private final List<UUID> createdStudentIds = new ArrayList<>();
    private final List<String> createdRedisKeys = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        for (UUID resultId : createdResultIds) {
            examResultRepository.deleteById(resultId);
        }
        for (UUID sessionId : createdSessionIds) {
            examSessionRepository.deleteById(sessionId);
        }
        for (UUID studentId : createdStudentIds) {
            studentRepository.deleteById(studentId);
        }
        for (String rk : createdRedisKeys) {
            stringRedisTemplate.delete(rk);
        }
        stringRedisTemplate.delete("studentprep:global_leaderboard");
    }

    private String issueToken(String identifier, String role) {
        String jti = UUID.randomUUID().toString();
        String redisKey = SessionRedisKeys.sessionKey(identifier);
        stringRedisTemplate.opsForValue().set(redisKey, jti, Duration.ofMinutes(5));
        createdRedisKeys.add(redisKey);
        return jwtUtil.generateToken(identifier, role, jti);
    }

    @Test
    void outboxRecoveryScheduler_InvokesIncompleteEventPublicationsUnderLock() {
        IncompleteEventPublications mockPublications = mock(IncompleteEventPublications.class);
        OutboxRecoveryScheduler scheduler = new OutboxRecoveryScheduler(mockPublications, 30);

        scheduler.sweepIncompletePublications();

        verify(mockPublications).resubmitIncompletePublicationsOlderThan(Duration.ofSeconds(30));
    }

    @Test
    void examCacheService_FallsBackToDatabase_WhenRedisThrowsDataAccessException() {
        QuestionInternalAPI mockQuestionAPI = mock(QuestionInternalAPI.class);
        @SuppressWarnings("unchecked")
        RedisTemplate<String, Object> mockRedisTemplate = mock(RedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, Object> mockValueOps = mock(ValueOperations.class);

        when(mockRedisTemplate.opsForValue()).thenReturn(mockValueOps);
        when(mockValueOps.get("exam:questions:all")).thenThrow(new QueryTimeoutException("Redis connection timed out"));

        Question fallbackQuestion = new Question();
        fallbackQuestion.setId(UUID.randomUUID());
        fallbackQuestion.setStatus(QuestionStatus.ACTIVE);
        when(mockQuestionAPI.getActiveQuestions()).thenReturn(List.of(fallbackQuestion));

        ExamCacheService resilientCacheService = new ExamCacheService(mockQuestionAPI, mockRedisTemplate);

        List<Question> questions = resilientCacheService.getCachedQuestions();

        assertThat(questions).singleElement().isEqualTo(fallbackQuestion);
        verify(mockQuestionAPI).getActiveQuestions();
    }

    @Test
    void questionService_EvictCache_FailsOpen_WhenRedisThrowsDataAccessException() {
        QuestionRepository mockRepo = mock(QuestionRepository.class);
        @SuppressWarnings("unchecked")
        RedisTemplate<String, Object> mockRedisTemplate = mock(RedisTemplate.class);

        doThrow(new QueryTimeoutException("Redis down")).when(mockRedisTemplate).delete("exam:questions:all");

        UUID questionId = UUID.randomUUID();
        Question existing = new Question();
        existing.setId(questionId);
        existing.setStatus(QuestionStatus.DRAFT);
        when(mockRepo.findById(questionId)).thenReturn(java.util.Optional.of(existing));
        when(mockRepo.save(any(Question.class))).thenReturn(existing);

        QuestionService questionService = new QuestionService(mockRepo, null, mockRedisTemplate);

        Question updateReq = new Question();
        updateReq.setStatus(QuestionStatus.ACTIVE);

        // Does not throw DataAccessException despite Redis failure
        Question updated = questionService.updateQuestionStatus(questionId, updateReq);
        assertThat(updated.getStatus()).isEqualTo(QuestionStatus.ACTIVE);
    }

    @Test
    void leaderboardService_IdempotentUpdatesAndBoundsClamping() {
        String studentId = UUID.randomUUID().toString();

        // Calling updateScore multiple times does not inflate score (uses .add instead of .incrementScore)
        leaderboardService.updateScore(studentId, 45.0);
        leaderboardService.updateScore(studentId, 45.0);

        List<Map<String, Object>> board = leaderboardService.getLeaderboard(10);
        assertThat(board).anySatisfy(entry -> {
            if (studentId.equals(entry.get("studentId"))) {
                assertThat(entry.get("score")).isEqualTo(45.0);
            }
        });

        // Test clamping: negative topN returns top 1, excessive topN is clamped
        List<Map<String, Object>> minBoard = leaderboardService.getLeaderboard(-5);
        assertThat(minBoard.size()).isLessThanOrEqualTo(1);

        List<Map<String, Object>> maxBoard = leaderboardService.getLeaderboard(500);
        assertThat(maxBoard.size()).isLessThanOrEqualTo(100);

        // Test removeEntry
        leaderboardService.removeEntry(studentId);
        List<Map<String, Object>> boardAfterRemove = leaderboardService.getLeaderboard(10);
        assertThat(boardAfterRemove).noneMatch(entry -> studentId.equals(entry.get("studentId")));
    }

    @Test
    void leaderboardService_HandlesRedisExceptionGracefully() {
        StringRedisTemplate mockRedis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ZSetOperations<String, String> mockZSet = mock(ZSetOperations.class);

        when(mockRedis.opsForZSet()).thenReturn(mockZSet);
        when(mockZSet.add(any(), any(), any(Double.class))).thenThrow(new QueryTimeoutException("Redis timeout"));
        when(mockZSet.reverseRangeWithScores(any(), any(Long.class), any(Long.class)))
                .thenThrow(new QueryTimeoutException("Redis timeout"));

        LeaderboardService resilientLeaderboard = new LeaderboardService(mockRedis);

        // Neither updateScore nor getLeaderboard crashes
        resilientLeaderboard.updateScore("student-x", 80.0);
        List<Map<String, Object>> results = resilientLeaderboard.getLeaderboard(10);
        assertThat(results).isEmpty();
    }

    @Test
    void analyticsController_GetResults_HandlesNullTopicBreakdownWithoutNPE() throws Exception {
        Student student = new Student();
        student.setName("Resilience Student");
        student.setRegistrationNumber("RESIL001");
        student.setPinHash("dummy_hash_resil");
        student.setPinResetRequired(false);
        student = studentRepository.save(student);
        createdStudentIds.add(student.getId());

        ExamSession examSession = new ExamSession();
        examSession.setUserId(student.getId());
        examSession.setStartTime(Instant.now());
        examSession.setStatus(ExamSessionStatus.SUBMITTED);
        examSession = examSessionRepository.save(examSession);
        createdSessionIds.add(examSession.getId());

        ExamResult nullBreakdownResult = new ExamResult();
        nullBreakdownResult.setStudent(student);
        nullBreakdownResult.setExamSessionId(examSession.getId());
        nullBreakdownResult.setTotalScore(50);
        nullBreakdownResult.setMaxScore(100);
        nullBreakdownResult.setTopicBreakdown(null); // Explicit null breakdown!
        nullBreakdownResult.setModerationStatus(ExamResultModerationStatus.ELIGIBLE);
        final ExamResult savedResult = examResultRepository.save(nullBreakdownResult);
        createdResultIds.add(savedResult.getId());

        // Test via controller directly
        ResponseEntity<PagedResponse<List<Map<String, Object>>>> response =
                analyticsController.getResults(0, 10);
        assertThat(response.getBody()).isNotNull();
        List<Map<String, Object>> list = response.getBody().getData();
        assertThat(list).anySatisfy(item -> {
            if (savedResult.getId().equals(item.get("id"))) {
                assertThat(item.get("topicBreakdown")).isEqualTo(Collections.emptyMap());
                assertThat(item.get("moderationStatus")).isEqualTo("ELIGIBLE");
                assertThat(item.get("sessionStatus")).isEqualTo("SUBMITTED");
                assertThat(item.get("examSessionId")).isEqualTo(savedResult.getExamSessionId());
            }
        });

        // Test via MockMvc
        String adminToken = issueToken("ADMIN_RESILIENCE", "ROLE_ADMIN");
        mockMvc.perform(get("/api/v1/admin/analytics/results")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .param("page", "0")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray());
    }
}
