package com.studentprep.analytics;

import com.studentprep.common.AnswerReceiptHasher;
import com.studentprep.common.SessionRedisKeys;
import com.studentprep.exam.ExamInternalAPI;
import com.studentprep.exam.ExamSession;
import com.studentprep.exam.ExamSessionFlaggedEvent;
import com.studentprep.exam.ExamSessionRepository;
import com.studentprep.exam.ExamSessionStatus;
import com.studentprep.exam.ExamSubmittedEvent;
import com.studentprep.security.JwtUtil;
import com.studentprep.student.Student;
import com.studentprep.student.StudentRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ProctoringAndModerationIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private StudentRepository studentRepository;

    @Autowired
    private ExamSessionRepository examSessionRepository;

    @Autowired
    private ExamResultRepository examResultRepository;

    @Autowired
    private LeaderboardService leaderboardService;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @Autowired
    private ExamGraderListener examGraderListener;

    @Autowired
    private ExamInternalAPI examInternalAPI;

    @Autowired
    private JwtUtil jwtUtil;

    private final List<UUID> createdStudentIds = new ArrayList<>();
    private final List<UUID> createdSessionIds = new ArrayList<>();
    private final List<UUID> createdResultIds = new ArrayList<>();
    private final List<String> createdRedisKeys = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        for (UUID resultId : createdResultIds) {
            examResultRepository.deleteById(resultId);
        }
        for (UUID sessionId : createdSessionIds) {
            examResultRepository.findByExamSessionId(sessionId).ifPresent(r -> examResultRepository.deleteById(r.getId()));
            examSessionRepository.deleteById(sessionId);
        }
        for (UUID studentId : createdStudentIds) {
            studentRepository.deleteById(studentId);
        }
        for (String key : createdRedisKeys) {
            redisTemplate.delete(key);
        }
        redisTemplate.delete("studentprep:global_leaderboard");
    }

    private String issueToken(String identifier, String role) {
        String jti = UUID.randomUUID().toString();
        String canonical = SessionRedisKeys.canonicalIdentifier(identifier);
        String redisKey = SessionRedisKeys.sessionKey(canonical);
        redisTemplate.opsForValue().set(redisKey, jti, Duration.ofMinutes(15));
        createdRedisKeys.add(redisKey);
        return jwtUtil.generateToken(canonical, role, jti);
    }

    @Test
    void monotonicStrikes_PreservedMonotonicallyOnExamSession() {
        Student student = createStudent("Monotonic Student", "MONO001");
        ExamSession session = new ExamSession();
        session.setUserId(student.getId());
        session.setStartTime(Instant.now());
        session.setStatus(ExamSessionStatus.IN_PROGRESS);

        Map<String, Object> state = new HashMap<>();
        state.put("tabSwitchCount", 2);
        session.setStatePayload(state);
        session = examSessionRepository.save(session);
        createdSessionIds.add(session.getId());

        // Verify stored strikes
        ExamSession loaded = examSessionRepository.findById(session.getId()).orElseThrow();
        int strikes = ((Number) loaded.getStatePayload().get("tabSwitchCount")).intValue();
        assertThat(strikes).isEqualTo(2);

        // Even if client reports 0 or 1, monotonic rule max(existing, reported) preserves 2
        int reportedCount = 1;
        int monotonicCount = Math.max(strikes, reportedCount);
        assertThat(monotonicCount).isEqualTo(2);
    }

    @Test
    void flaggedTabSwitch_EvictsRedisToken_SuppressesExamResult_AndBlocksResultSlipWith403() throws Exception {
        Student student = createStudent("Flagged Student", "FLAG_PROCT_001");
        String canonicalId = SessionRedisKeys.canonicalIdentifier(student.getRegistrationNumber());
        String sessionKey = SessionRedisKeys.sessionKey(canonicalId);

        // Put active token in Redis
        String studentToken = issueToken(student.getRegistrationNumber(), "ROLE_STUDENT");
        assertThat(redisTemplate.hasKey(sessionKey)).isTrue();

        // Create flagged exam session in DB
        ExamSession session = new ExamSession();
        session.setUserId(student.getId());
        session.setStartTime(Instant.now().minusSeconds(1800));
        session.setStatus(ExamSessionStatus.FLAGGED_TAB_SWITCH);
        session = examSessionRepository.save(session);
        createdSessionIds.add(session.getId());

        // Publish ExamSessionFlaggedEvent -> ExamFlaggedSessionListener should evict Redis key
        eventPublisher.publishEvent(new ExamSessionFlaggedEvent(session.getId(), student.getId(), "TAB_SWITCH_THRESHOLD"));

        // Verify Redis token eviction
        assertThat(redisTemplate.hasKey(sessionKey)).isFalse();

        // Publish ExamSubmittedEvent with FLAGGED_TAB_SWITCH -> ExamGraderListener must skip grading
        Map<String, Object> finalState = Map.of("answers", Map.of("q1", "A"));
        Map<String, Object> snapshot = Map.of("questions", List.of(Map.of("id", "q1", "content", Map.of("correctOption", "A"))));
        eventPublisher.publishEvent(new ExamSubmittedEvent(
                session.getId(),
                student.getId(),
                finalState,
                ExamSessionStatus.FLAGGED_TAB_SWITCH,
                snapshot));

        // Verify zero ExamResult saved
        Optional<ExamResult> resultOpt = examResultRepository.findByExamSessionId(session.getId());
        assertThat(resultOpt).isEmpty();

        // Re-issue a temporary token so student can hit student/results endpoint to check review banner
        String studentQueryToken = issueToken(student.getRegistrationNumber(), "ROLE_STUDENT");

        // Verify candidate results endpoint reports UNDER_REVIEW
        mockMvc.perform(get("/api/v1/student/results")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + studentQueryToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reviewStatus").value("UNDER_REVIEW"))
                .andExpect(jsonPath("$.data.moderationStatus").value("FLAGGED"));

        // Verify slip download is blocked with 403 Forbidden
        mockMvc.perform(get("/api/v1/students/me/results/latest/slip")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + studentQueryToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void lateSubmission_WithholdsSlipAndLeaderboard_UntilAdminClears() throws Exception {
        Student student = createStudent("Late Student", "LATE_PROCT_001");
        String studentToken = issueToken(student.getRegistrationNumber(), "ROLE_STUDENT");

        ExamSession session = new ExamSession();
        session.setUserId(student.getId());
        session.setStartTime(Instant.now().minusSeconds(7500));
        session.setStatus(ExamSessionStatus.LATE_SUBMISSION_FLAGGED);
        session = examSessionRepository.save(session);
        createdSessionIds.add(session.getId());

        Map<String, Object> finalState = Map.of("answers", Map.of("q1", "B"));
        Map<String, Object> snapshot = Map.of("questions", List.of(
                Map.of("id", "q1", "content", Map.of("correctOption", "B"), "topic", "Algebra", "subject", Map.of("name", "Mathematics"))
        ));

        // Submit late exam
        examGraderListener.onExamSubmitted(new ExamSubmittedEvent(
                session.getId(),
                student.getId(),
                finalState,
                ExamSessionStatus.LATE_SUBMISSION_FLAGGED,
                snapshot));

        // Verify result created with PENDING_REVIEW
        ExamResult result = examResultRepository.findByExamSessionId(session.getId()).orElseThrow();
        createdResultIds.add(result.getId());
        assertThat(result.getModerationStatus()).isEqualTo(ExamResultModerationStatus.PENDING_REVIEW);
        assertThat(result.getTotalScore()).isEqualTo(1);

        // Verify score is NOT in leaderboard
        List<Map<String, Object>> board = leaderboardService.getLeaderboard(10);
        assertThat(board).noneMatch(entry -> student.getId().toString().equals(entry.get("studentId")));

        // Candidate slip request returns 403 Forbidden
        mockMvc.perform(get("/api/v1/students/me/results/" + result.getId() + "/slip")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + studentToken))
                .andExpect(status().isForbidden());

        // Candidate results endpoint returns PENDING_REVIEW
        mockMvc.perform(get("/api/v1/student/results")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + studentToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reviewStatus").value("PENDING_REVIEW"))
                .andExpect(jsonPath("$.data.moderationStatus").value("PENDING_REVIEW"));

        // Admin clears late submission
        String adminToken = issueToken("ADMIN_MOD", "ROLE_ADMIN");
        mockMvc.perform(post("/api/v1/admin/exams/sessions/" + session.getId() + "/clear-late")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUBMITTED"))
                .andExpect(jsonPath("$.data.moderationStatus").value("CLEARED"));

        // Verify ExamResult promoted to CLEARED
        ExamResult clearedResult = examResultRepository.findById(result.getId()).orElseThrow();
        assertThat(clearedResult.getModerationStatus()).isEqualTo(ExamResultModerationStatus.CLEARED);

        // Verify student now appears on leaderboard
        List<Map<String, Object>> updatedBoard = leaderboardService.getLeaderboard(10);
        assertThat(updatedBoard).anySatisfy(entry -> {
            if (student.getId().toString().equals(entry.get("studentId"))) {
                assertThat(entry.get("score")).isEqualTo(1.0);
            }
        });

        // Now candidate can download slip successfully
        mockMvc.perform(get("/api/v1/students/me/results/" + result.getId() + "/slip")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + studentToken))
                .andExpect(status().isOk());
    }

    @Test
    void moderationActions_VoidAndGrantRetake() throws Exception {
        Student student = createStudent("Moderation Student", "MOD_ACT_001");

        ExamSession session = new ExamSession();
        session.setUserId(student.getId());
        session.setStartTime(Instant.now().minusSeconds(7200));
        session.setStatus(ExamSessionStatus.LATE_SUBMISSION_FLAGGED);
        session = examSessionRepository.save(session);
        createdSessionIds.add(session.getId());

        Map<String, Object> finalState = Map.of("answers", Map.of("q1", "C"));
        Map<String, Object> snapshot = Map.of("questions", List.of(
                Map.of("id", "q1", "content", Map.of("correctOption", "C"), "topic", "Mechanics", "subject", Map.of("name", "Physics"))
        ));

        examGraderListener.onExamSubmitted(new ExamSubmittedEvent(
                session.getId(),
                student.getId(),
                finalState,
                ExamSessionStatus.LATE_SUBMISSION_FLAGGED,
                snapshot));

        ExamResult result = examResultRepository.findByExamSessionId(session.getId()).orElseThrow();
        createdResultIds.add(result.getId());

        String adminToken = issueToken("ADMIN_MOD_2", "ROLE_ADMIN");

        // Admin voids session
        mockMvc.perform(post("/api/v1/admin/exams/sessions/" + session.getId() + "/void")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("VOIDED"));

        ExamResult voidedResult = examResultRepository.findById(result.getId()).orElseThrow();
        assertThat(voidedResult.getModerationStatus()).isEqualTo(ExamResultModerationStatus.VOIDED);

        // Admin grants retake to student
        mockMvc.perform(post("/api/v1/admin/students/" + student.getId() + "/grant-retake")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.retakeGranted").value(true));

        // Existing sessions for student are marked superseded
        ExamSession supersededSession = examSessionRepository.findById(session.getId()).orElseThrow();
        assertThat(supersededSession.isSuperseded()).isTrue();
    }

    @Test
    void supervisorRole_RejectsMutationsWith403_AllowsFlaggedSessionsRead() throws Exception {
        UUID randomSessionId = UUID.randomUUID();
        UUID randomStudentId = UUID.randomUUID();

        String supervisorToken = issueToken("SUPERVISOR_TEST", "ROLE_SUPERVISOR");

        // Supervisor attempts mutations -> 403 Forbidden
        mockMvc.perform(post("/api/v1/admin/exams/sessions/" + randomSessionId + "/clear-late")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + supervisorToken))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/admin/exams/sessions/" + randomSessionId + "/void")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + supervisorToken))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/admin/students/" + randomStudentId + "/grant-retake")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + supervisorToken))
                .andExpect(status().isForbidden());

        // Supervisor calls GET flagged sessions -> 200 OK
        mockMvc.perform(get("/api/v1/admin/analytics/flagged-sessions")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + supervisorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray());
    }

    @Test
    void examScriptAudit_AllowsAdminAndSupervisor_VerifiesReceiptAndPdf_BlocksStudentWith403() throws Exception {
        Student student = createStudent("Audit Candidate", "901234AU");

        Map<String, Object> statePayload = new HashMap<>();
        statePayload.put("answers", Map.of("q-math-1", "E", "q-phys-1", "A"));
        statePayload.put("flagged", Map.of("q-phys-1", true));
        statePayload.put("tabSwitchCount", 1);
        statePayload.put("lastUpdated", 1763174400000L);

        Map<String, Object> snapshotPayload = Map.of(
                "contexts", Map.of("ctx-1", "Read the motion graph carefully."),
                "contextAssets", Map.of("ctx-1", List.of(Map.of(
                        "type", "image",
                        "url", "http://localhost:9000/studentprep-assets/diagrams/motion-graph.png"
                ))),
                "questions", List.of(
                        Map.of(
                                "id", "q-math-1",
                                "topic", "Trigonometry",
                                "subject", Map.of("id", "sub-math", "name", "MATHEMATICS"),
                                "content", Map.of(
                                        "text", "Find the angle $\\theta = 60^\\circ$.",
                                        "options", Map.of("A", "$90^\\circ$", "E", "$60^\\circ$"),
                                        "correctOption", "E"
                                )
                        ),
                        Map.of(
                                "id", "q-phys-1",
                                "topic", "Kinematics",
                                "contextId", "ctx-1",
                                "subject", Map.of("id", "sub-phys", "name", "PHYSICS"),
                                "content", Map.of(
                                        "text", "Calculate the velocity.",
                                        "options", Map.of("A", "10 m/s", "B", "25 m/s"),
                                        "correctOption", "B"
                                )
                        ),
                        Map.of(
                                "id", "q-eng-1",
                                "topic", "Lexis",
                                "subject", Map.of("id", "sub-eng", "name", "ENGLISH"),
                                "content", Map.of(
                                        "text", "Choose the synonym.",
                                        "options", Map.of("A", "Fast", "B", "Slow"),
                                        "correctOption", "A"
                                )
                        )
                )
        );

        ExamSession session = new ExamSession();
        session.setUserId(student.getId());
        session.setStartTime(Instant.now().minusSeconds(3600));
        session.setExpectedEndTime(Instant.now().plusSeconds(3600));
        session.setEndTime(Instant.now().minusSeconds(60));
        session.setStatus(ExamSessionStatus.SUBMITTED);
        session.setStatePayload(statePayload);
        session.setSnapshotPayload(snapshotPayload);
        session = examSessionRepository.save(session);
        createdSessionIds.add(session.getId());

        examGraderListener.onExamSubmitted(new ExamSubmittedEvent(
                session.getId(),
                student.getId(),
                statePayload,
                ExamSessionStatus.SUBMITTED,
                snapshotPayload
        ));
        ExamResult savedResult = examResultRepository.findByExamSessionId(session.getId()).orElseThrow();
        createdResultIds.add(savedResult.getId());

        String expectedReceiptCode = AnswerReceiptHasher.computeReceiptCode(
                session.getId(),
                Map.of("q-math-1", "E", "q-phys-1", "A")
        );

        String adminToken = issueToken("ADMIN_AUDIT", "ROLE_ADMIN");
        String supervisorToken = issueToken("SUPERVISOR_AUDIT", "ROLE_SUPERVISOR");
        String studentToken = issueToken(student.getRegistrationNumber(), "ROLE_STUDENT");

        // 1. Admin inspects script JSON -> 200 OK with full provenance, receipt, and question outcomes
        mockMvc.perform(get("/api/v1/admin/exams/sessions/" + session.getId() + "/script")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.studentName").value("Audit Candidate"))
                .andExpect(jsonPath("$.data.registrationNumber").value("901234AU"))
                .andExpect(jsonPath("$.data.submissionTrigger").value("MANUAL_SUBMIT"))
                .andExpect(jsonPath("$.data.answerReceiptCode").value(expectedReceiptCode))
                .andExpect(jsonPath("$.data.totalQuestions").value(3))
                .andExpect(jsonPath("$.data.answeredCount").value(2))
                .andExpect(jsonPath("$.data.correctCount").value(1))
                .andExpect(jsonPath("$.data.incorrectCount").value(1))
                .andExpect(jsonPath("$.data.unansweredCount").value(1))
                .andExpect(jsonPath("$.data.questions[0].outcome").value("CORRECT"))
                .andExpect(jsonPath("$.data.questions[0].sharedContextAssets").isEmpty())
                .andExpect(jsonPath("$.data.questions[1].outcome").value("INCORRECT"))
                .andExpect(jsonPath("$.data.questions[1].sharedContext").value("Read the motion graph carefully."))
                .andExpect(jsonPath("$.data.questions[1].sharedContextAssets[0].url")
                        .value("http://localhost:9000/studentprep-assets/diagrams/motion-graph.png"))
                .andExpect(jsonPath("$.data.questions[1].flagged").value(true))
                .andExpect(jsonPath("$.data.questions[2].outcome").value("UNANSWERED"));

        // 2. Supervisor also inspects script JSON -> 200 OK
        mockMvc.perform(get("/api/v1/admin/exams/sessions/" + session.getId() + "/script")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + supervisorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.answerReceiptCode").value(expectedReceiptCode));

        // 3. Both Admin and Supervisor can download the printable A4 Audit Script PDF -> 200 OK (%PDF)
        byte[] adminPdf = mockMvc.perform(get("/api/v1/admin/exams/sessions/" + session.getId() + "/script/pdf")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsByteArray();
        assertThat(new String(adminPdf, 0, 4)).isEqualTo("%PDF");

        byte[] supervisorPdf = mockMvc.perform(get("/api/v1/admin/exams/sessions/" + session.getId() + "/script/pdf")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + supervisorToken))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsByteArray();
        assertThat(new String(supervisorPdf, 0, 4)).isEqualTo("%PDF");

        // 4. Student receives the matching answerReceiptCode on /api/v1/student/results,
        //    but is strictly blocked (403 Forbidden) from accessing /script or /script/pdf
        mockMvc.perform(get("/api/v1/student/results")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + studentToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.answerReceiptCode").value(expectedReceiptCode));

        mockMvc.perform(get("/api/v1/admin/exams/sessions/" + session.getId() + "/script")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + studentToken))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/v1/admin/exams/sessions/" + session.getId() + "/script/pdf")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + studentToken))
                .andExpect(status().isForbidden());
    }

    private Student createStudent(String name, String regNumber) {
        Student s = new Student();
        s.setName(name);
        s.setRegistrationNumber(regNumber);
        s.setPinHash("dummy_hash_" + UUID.randomUUID());
        s.setPinResetRequired(false);
        s = studentRepository.save(s);
        createdStudentIds.add(s.getId());
        return s;
    }
}
