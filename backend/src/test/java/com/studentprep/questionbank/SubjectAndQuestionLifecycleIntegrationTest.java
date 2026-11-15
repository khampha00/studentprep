package com.studentprep.questionbank;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.studentprep.common.SessionRedisKeys;
import com.studentprep.questionbank.dto.QuestionUpdateRequest;
import com.studentprep.security.JwtUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class SubjectAndQuestionLifecycleIntegrationTest {

    private static final String QUESTIONS_CACHE_KEY = "exam:questions:all";

    private final MockMvc mockMvc;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbcTemplate;
    private final SubjectRepository subjectRepository;
    private final QuestionRepository questionRepository;
    private final QuestionContextRepository questionContextRepository;
    private final QuestionService questionService;
    private final StringRedisTemplate redisTemplate;
    private final JwtUtil jwtUtil;
    private final TransactionTemplate transactionTemplate;

    private final List<UUID> createdQuestionIds = new ArrayList<>();
    private final List<UUID> createdContextIds = new ArrayList<>();
    private final List<UUID> createdIngestionJobIds = new ArrayList<>();
    private final List<UUID> createdSubjectIds = new ArrayList<>();
    private final List<String> createdRedisKeys = new ArrayList<>();
    private final List<String> createdAdminIdentifiers = new ArrayList<>();

    SubjectAndQuestionLifecycleIntegrationTest(
            MockMvc mockMvc,
            ObjectMapper objectMapper,
            JdbcTemplate jdbcTemplate,
            SubjectRepository subjectRepository,
            QuestionRepository questionRepository,
            QuestionContextRepository questionContextRepository,
            QuestionService questionService,
            StringRedisTemplate redisTemplate,
            JwtUtil jwtUtil,
            TransactionTemplate transactionTemplate) {
        this.mockMvc = mockMvc;
        this.objectMapper = objectMapper;
        this.jdbcTemplate = jdbcTemplate;
        this.subjectRepository = subjectRepository;
        this.questionRepository = questionRepository;
        this.questionContextRepository = questionContextRepository;
        this.questionService = questionService;
        this.redisTemplate = redisTemplate;
        this.jwtUtil = jwtUtil;
        this.transactionTemplate = transactionTemplate;
    }

    @AfterEach
    void cleanup() {
        for (UUID questionId : createdQuestionIds) {
            if (questionRepository.existsById(questionId)) {
                questionRepository.deleteById(questionId);
            }
        }
        for (UUID contextId : createdContextIds) {
            if (questionContextRepository.existsById(contextId)) {
                questionContextRepository.deleteById(contextId);
            }
        }
        for (UUID jobId : createdIngestionJobIds) {
            jdbcTemplate.update("DELETE FROM ingestion_jobs WHERE id = ?", jobId);
        }
        for (UUID subjectId : createdSubjectIds) {
            if (subjectRepository.existsById(subjectId)) {
                questionContextRepository.deleteBySubject_Id(subjectId);
                subjectRepository.deleteById(subjectId);
            }
        }
        for (String adminIdentifier : createdAdminIdentifiers) {
            jdbcTemplate.update("DELETE FROM audit_logs WHERE username = ?", adminIdentifier);
        }
        for (String key : createdRedisKeys) {
            redisTemplate.delete(key);
        }
        redisTemplate.delete(QUESTIONS_CACHE_KEY);

        createdQuestionIds.clear();
        createdContextIds.clear();
        createdIngestionJobIds.clear();
        createdSubjectIds.clear();
        createdRedisKeys.clear();
        createdAdminIdentifiers.clear();
    }

    private String issueAdminToken(String prefix) {
        String identifier = prefix + "_" + UUID.randomUUID().toString().substring(0, 8);
        String canonical = SessionRedisKeys.canonicalIdentifier(identifier);
        String jti = UUID.randomUUID().toString();
        String redisKey = SessionRedisKeys.sessionKey(canonical);
        redisTemplate.opsForValue().set(redisKey, jti, Duration.ofMinutes(15));
        createdRedisKeys.add(redisKey);
        createdAdminIdentifiers.add(canonical);
        return jwtUtil.generateToken(canonical, "ROLE_ADMIN", jti);
    }

    private Subject createSubject(String prefix) {
        Subject subject = new Subject(prefix + "_" + UUID.randomUUID().toString().substring(0, 8));
        Subject saved = subjectRepository.save(subject);
        createdSubjectIds.add(saved.getId());
        return saved;
    }

    @Test
    void deleteSubject_Returns409WhenQuestionsExist_And200CleansOrphanContextsAndCascadesIngestionJobsWhenEmpty()
            throws Exception {
        String adminToken = issueAdminToken("ADMIN_SUBJ_DEL");
        Subject subject = createSubject("LIFECYCLE_DEL");

        QuestionContext context = new QuestionContext();
        context.setSubject(subject);
        context.setPassage("Orphaned passage from earlier failed extraction.");
        context = questionContextRepository.save(context);
        createdContextIds.add(context.getId());

        UUID jobId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO ingestion_jobs (id, subject_id, status, total_chunks, processed_chunks, error_message) "
                        + "VALUES (?, ?, ?, 1, 0, ?)",
                jobId,
                subject.getId(),
                "FAILED",
                "Simulated upstream failure");
        createdIngestionJobIds.add(jobId);

        Question draftQuestion = new Question();
        draftQuestion.setSubject(subject);
        draftQuestion.setContext(context);
        draftQuestion.setIngestionJobId(jobId);
        draftQuestion.setStatus(QuestionStatus.DRAFT);
        draftQuestion.setTopic("Mechanics");
        draftQuestion.setContent(Map.of(
                "type", "MCQ",
                "text", "What is Newton's second law?",
                "options", Map.of("A", "F=ma", "B", "E=mc2")));
        draftQuestion = questionRepository.save(draftQuestion);
        createdQuestionIds.add(draftQuestion.getId());

        redisTemplate.opsForValue().set(QUESTIONS_CACHE_KEY, "cached-questions");

        // Deleting a subject while questions still exist must return 409 Conflict ProblemDetail
        mockMvc.perform(delete("/api/v1/admin/subjects/" + subject.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.detail").value(
                        "Cannot delete subject while questions remain. Delete active and draft questions first."));

        assertThat(subjectRepository.existsById(subject.getId())).isTrue();
        assertThat(redisTemplate.hasKey(QUESTIONS_CACHE_KEY)).isTrue();

        // Remove the blocking question (leaving orphaned context + historical ingestion job)
        mockMvc.perform(delete("/api/v1/admin/questions/" + draftQuestion.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk());

        // Re-seed cache marker and confirm empty subject deletes cleanly, cleaning contexts and jobs
        redisTemplate.opsForValue().set(QUESTIONS_CACHE_KEY, "cached-questions");

        mockMvc.perform(delete("/api/v1/admin/subjects/" + subject.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DELETED"));

        assertThat(subjectRepository.existsById(subject.getId())).isFalse();
        assertThat(questionContextRepository.existsById(context.getId())).isFalse();
        Integer remainingJobs = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ingestion_jobs WHERE id = ?",
                Integer.class,
                jobId);
        assertThat(remainingJobs).isZero();
        assertThat(redisTemplate.hasKey(QUESTIONS_CACHE_KEY)).isFalse();

        // Deleting a missing subject returns 404 Not Found
        mockMvc.perform(delete("/api/v1/admin/subjects/" + UUID.randomUUID())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void updateQuestion_ValidatesQuestionUpdateRequest_UpdatesFields_AndEvictsCacheOnlyAfterCommit()
            throws Exception {
        String adminToken = issueAdminToken("ADMIN_Q_UPD");
        Subject subject = createSubject("LIFECYCLE_UPD");

        Question draftQuestion = new Question();
        draftQuestion.setSubject(subject);
        draftQuestion.setStatus(QuestionStatus.DRAFT);
        draftQuestion.setTopic("Initial Topic");
        draftQuestion.setContent(Map.of(
                "type", "MCQ",
                "text", "Initial prompt",
                "options", Map.of("A", "1", "B", "2")));
        draftQuestion = questionRepository.save(draftQuestion);
        createdQuestionIds.add(draftQuestion.getId());

        // 1. Missing status -> 400 Bad Request ProblemDetail
        mockMvc.perform(put("/api/v1/admin/questions/" + draftQuestion.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "topic", "Algebra",
                                "content", Map.of("text", "2+2=?")))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.errors[*].field", hasItem("status")));

        // 2. Missing content -> 400 Bad Request ProblemDetail
        mockMvc.perform(put("/api/v1/admin/questions/" + draftQuestion.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "status", "DRAFT",
                                "topic", "Algebra"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.errors[*].field", hasItem("content")));

        // 3. Topic > 255 chars -> 400 Bad Request ProblemDetail
        mockMvc.perform(put("/api/v1/admin/questions/" + draftQuestion.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "status", "DRAFT",
                                "topic", "T".repeat(256),
                                "content", Map.of("text", "2+2=?")))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.errors[*].field", hasItem("topic")));

        // 4. Promoting to ACTIVE without correctOption -> 400 Bad Request
        mockMvc.perform(put("/api/v1/admin/questions/" + draftQuestion.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "status", "ACTIVE",
                                "topic", "Algebra",
                                "content", Map.of(
                                        "type", "MCQ",
                                        "text", "Solve 2x = 6",
                                        "options", Map.of("A", "2", "B", "3"))))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));

        // 5. Promoting to ACTIVE with blank text -> 400 Bad Request
        mockMvc.perform(put("/api/v1/admin/questions/" + draftQuestion.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "status", "ACTIVE",
                                "topic", "Algebra",
                                "content", Map.of(
                                        "type", "MCQ",
                                        "text", "   ",
                                        "options", Map.of("A", "2", "B", "3"),
                                        "correctOption", "B")))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));

        // 6. Promoting to ACTIVE with correctOption absent from options -> 400 Bad Request
        mockMvc.perform(put("/api/v1/admin/questions/" + draftQuestion.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "status", "ACTIVE",
                                "topic", "Algebra",
                                "content", Map.of(
                                        "type", "MCQ",
                                        "text", "Solve 2x = 6",
                                        "options", Map.of("A", "2", "B", "3"),
                                        "correctOption", "Z")))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));

        // 7. Unknown question ID -> 404 Not Found
        mockMvc.perform(put("/api/v1/admin/questions/" + UUID.randomUUID())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "status", "DRAFT",
                                "content", Map.of("text", "Any")))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));

        // 8. Cache eviction MUST NOT execute when the surrounding transaction rolls back
        redisTemplate.opsForValue().set(QUESTIONS_CACHE_KEY, "cached-questions");
        final UUID targetId = draftQuestion.getId();
        transactionTemplate.executeWithoutResult(status -> {
            questionService.updateQuestionStatus(
                    targetId,
                    new QuestionUpdateRequest(
                            QuestionStatus.ACTIVE,
                            "RolledBack",
                            Map.of(
                                    "type", "MCQ",
                                    "text", "Rolled back text",
                                    "options", Map.of("A", "2", "B", "3"),
                                    "correctOption", "B")));
            status.setRollbackOnly();
        });
        assertThat(redisTemplate.hasKey(QUESTIONS_CACHE_KEY)).isTrue();
        Question afterRollback = questionRepository.findById(targetId).orElseThrow();
        assertThat(afterRollback.getStatus()).isEqualTo(QuestionStatus.DRAFT);

        // 9. Valid promotion to ACTIVE (including extra UI payload keys) persists changes & evicts cache after commit
        Map<String, Object> validPayload = new LinkedHashMap<>();
        validPayload.put("id", targetId.toString());
        validPayload.put("subjectId", subject.getId().toString());
        validPayload.put("status", "ACTIVE");
        validPayload.put("topic", "Linear Algebra");
        validPayload.put("content", Map.of(
                "type", "MCQ",
                "text", "Solve 2x = 6",
                "options", Map.of("A", "2", "B", "3"),
                "correctOption", "B",
                "assets", List.of(Map.of("type", "image", "url", "https://cdn.example.com/diagram.png"))));

        mockMvc.perform(put("/api/v1/admin/questions/" + targetId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validPayload)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.topic").value("Linear Algebra"))
                .andExpect(jsonPath("$.data.content.correctOption").value("B"));

        Question persisted = questionRepository.findById(targetId).orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo(QuestionStatus.ACTIVE);
        assertThat(persisted.getTopic()).isEqualTo("Linear Algebra");
        assertThat(persisted.getContent().get("text")).isEqualTo("Solve 2x = 6");
        assertThat(persisted.getContent().get("correctOption")).isEqualTo("B");
        assertThat(redisTemplate.hasKey(QUESTIONS_CACHE_KEY)).isFalse();
    }

    @Test
    void relinkContextsForSubject_RespectsIsFollowUpFlagAndGapFillContinuation() throws Exception {
        String adminToken = issueAdminToken("ADMIN_RELINK");
        Subject subject = createSubject("LIFECYCLE_RELINK");

        QuestionContext staleContext = new QuestionContext();
        staleContext.setSubject(subject);
        staleContext.setPassage("Stale wrong passage");
        staleContext = questionContextRepository.save(staleContext);
        createdContextIds.add(staleContext.getId());

        Question q1 = createOrderedQuestion(subject, 1, null, Map.of(
                "text", "According to the passage, what happened?",
                "shared_context", "Passage Alpha: The river rises in spring."));
        Question q2 = createOrderedQuestion(subject, 2, null, Map.of(
                "text", "What is the author's tone?",
                "is_follow_up", true));
        // Previously mis-linked standalone question with is_follow_up: false must be unlinked and clear activeContext
        Question q3 = createOrderedQuestion(subject, 3, staleContext, Map.of(
                "text", "Calculate 5 + 7.",
                "is_follow_up", false));
        // A follow-up flag with no active passage before it must stay standalone
        Question q4 = createOrderedQuestion(subject, 4, null, Map.of(
                "text", "Follow-up after reset should remain unlinked.",
                "is_follow_up", true));
        Question q5 = createOrderedQuestion(subject, 5, null, Map.of(
                "text", "Read the cloze passage.",
                "shared_context", "Passage Beta: Education __12__ society."));
        // Missing is_follow_up but unmistakable "Fill in gap ..." continuation links to Passage Beta
        Question q6 = createOrderedQuestion(subject, 6, null, Map.of(
                "text", "Fill in gap 12"));
        // Missing is_follow_up and normal standalone text resets activeContext and stays unlinked
        Question q7 = createOrderedQuestion(subject, 7, null, Map.of(
                "text", "Which planet is closest to the Sun?"));

        mockMvc.perform(post("/api/v1/admin/questions/relink-contexts")
                        .param("subjectId", subject.getId().toString())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUCCESS"));

        Question reloaded1 = questionRepository.findById(q1.getId()).orElseThrow();
        Question reloaded2 = questionRepository.findById(q2.getId()).orElseThrow();
        Question reloaded3 = questionRepository.findById(q3.getId()).orElseThrow();
        Question reloaded4 = questionRepository.findById(q4.getId()).orElseThrow();
        Question reloaded5 = questionRepository.findById(q5.getId()).orElseThrow();
        Question reloaded6 = questionRepository.findById(q6.getId()).orElseThrow();
        Question reloaded7 = questionRepository.findById(q7.getId()).orElseThrow();

        if (reloaded1.getContext() != null) {
            createdContextIds.add(reloaded1.getContext().getId());
        }
        if (reloaded5.getContext() != null) {
            createdContextIds.add(reloaded5.getContext().getId());
        }

        assertThat(reloaded1.getContext()).isNotNull();
        assertThat(reloaded1.getContext().getPassage()).contains("Passage Alpha");
        assertThat(reloaded2.getContext()).isNotNull();
        assertThat(reloaded2.getContext().getId()).isEqualTo(reloaded1.getContext().getId());

        assertThat(reloaded3.getContext()).isNull();
        assertThat(reloaded4.getContext()).isNull();

        assertThat(reloaded5.getContext()).isNotNull();
        assertThat(reloaded5.getContext().getPassage()).contains("Passage Beta");
        assertThat(reloaded6.getContext()).isNotNull();
        assertThat(reloaded6.getContext().getId()).isEqualTo(reloaded5.getContext().getId());

        assertThat(reloaded7.getContext()).isNull();
    }

    private Question createOrderedQuestion(
            Subject subject,
            int offsetSeconds,
            QuestionContext initialContext,
            Map<String, Object> content) {
        Question q = new Question();
        q.setSubject(subject);
        q.setContext(initialContext);
        q.setStatus(QuestionStatus.DRAFT);
        q.setContent(new LinkedHashMap<>(content));
        Question saved = questionRepository.save(q);
        createdQuestionIds.add(saved.getId());
        Instant orderedTs = Instant.parse("2026-01-01T00:00:00Z").plusSeconds(offsetSeconds);
        jdbcTemplate.update("UPDATE questions SET created_at = ? WHERE id = ?", Timestamp.from(orderedTs), saved.getId());
        return saved;
    }

    @Test
    void updateContext_PersistsPassageAndAssets_EnforcesAdminRoleAndValidation_AndEvictsCache() throws Exception {
        String adminToken = issueAdminToken("ADMIN_CTX_UPD");
        String supervisorCanonical = SessionRedisKeys.canonicalIdentifier("SUP_CTX_UPD");
        String supJti = UUID.randomUUID().toString();
        String supRedisKey = SessionRedisKeys.sessionKey(supervisorCanonical);
        redisTemplate.opsForValue().set(supRedisKey, supJti, Duration.ofMinutes(15));
        createdRedisKeys.add(supRedisKey);
        String supervisorToken = jwtUtil.generateToken(supervisorCanonical, "ROLE_SUPERVISOR", supJti);

        Subject subject = createSubject("LIFECYCLE_CTX_UPD");
        QuestionContext context = new QuestionContext();
        context.setSubject(subject);
        context.setPassage("Use the diagram below to answer questions 3 and 4");
        context = questionContextRepository.save(context);
        createdContextIds.add(context.getId());

        String imageUrl = "http://localhost:9000/studentprep-assets/diagrams/yam-setup.png";

        // 1. Supervisor cannot mutate shared context -> 403 Forbidden
        mockMvc.perform(put("/api/v1/admin/questions/contexts/" + context.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + supervisorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "passage", "Unauthorized edit",
                                "assets", List.of(Map.of("type", "image", "url", imageUrl))))))
                .andExpect(status().isForbidden());

        // 2. Empty passage + empty assets -> 400 Bad Request
        mockMvc.perform(put("/api/v1/admin/questions/contexts/" + context.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "passage", "   ",
                                "assets", List.of()))))
                .andExpect(status().isBadRequest());

        // 3. Valid ADMIN update -> 200 OK, persists passage + assets in PostgreSQL, evicts Redis cache
        redisTemplate.opsForValue().set(QUESTIONS_CACHE_KEY, "cached-questions");
        mockMvc.perform(put("/api/v1/admin/questions/contexts/" + context.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "passage", "Updated biology diagram passage",
                                "assets", List.of(Map.of("type", "image", "url", imageUrl))))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.passage").value("Updated biology diagram passage"))
                .andExpect(jsonPath("$.data.assets[0].url").value(imageUrl));

        QuestionContext reloaded = questionContextRepository.findById(context.getId()).orElseThrow();
        assertThat(reloaded.getPassage()).isEqualTo("Updated biology diagram passage");
        assertThat(reloaded.getAssets()).hasSize(1);
        assertThat(reloaded.getAssets().get(0).get("url")).isEqualTo(imageUrl);
        assertThat(redisTemplate.hasKey(QUESTIONS_CACHE_KEY)).isFalse();

        // 4. Unknown context -> 404 Not Found
        mockMvc.perform(put("/api/v1/admin/questions/contexts/" + UUID.randomUUID())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "passage", "Missing context",
                                "assets", List.of()))))
                .andExpect(status().isNotFound());
    }
}
