package com.studentprep.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.studentprep.common.SessionRedisKeys;
import com.studentprep.exam.ExamSession;
import com.studentprep.exam.ExamSessionRepository;
import com.studentprep.exam.ExamSessionStatus;
import com.studentprep.questionbank.Subject;
import com.studentprep.questionbank.SubjectRepository;
import com.studentprep.student.Student;
import com.studentprep.student.StudentPinCryptoService;
import com.studentprep.student.StudentRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class AuditLogAspectIntegrationTest {

    private final MockMvc mockMvc;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbcTemplate;
    private final StringRedisTemplate redisTemplate;
    private final JwtUtil jwtUtil;
    private final SubjectRepository subjectRepository;
    private final StudentRepository studentRepository;
    private final StudentPinCryptoService studentPinCryptoService;
    private final ExamSessionRepository examSessionRepository;

    private final List<String> createdAdminIdentifiers = new ArrayList<>();
    private final List<String> createdRedisKeys = new ArrayList<>();
    private final List<UUID> createdJobIds = new ArrayList<>();
    private final List<UUID> createdSubjectIds = new ArrayList<>();
    private final List<UUID> createdSessionIds = new ArrayList<>();
    private final List<UUID> createdStudentIds = new ArrayList<>();

    AuditLogAspectIntegrationTest(
            MockMvc mockMvc,
            ObjectMapper objectMapper,
            JdbcTemplate jdbcTemplate,
            StringRedisTemplate redisTemplate,
            JwtUtil jwtUtil,
            SubjectRepository subjectRepository,
            StudentRepository studentRepository,
            StudentPinCryptoService studentPinCryptoService,
            ExamSessionRepository examSessionRepository) {
        this.mockMvc = mockMvc;
        this.objectMapper = objectMapper;
        this.jdbcTemplate = jdbcTemplate;
        this.redisTemplate = redisTemplate;
        this.jwtUtil = jwtUtil;
        this.subjectRepository = subjectRepository;
        this.studentRepository = studentRepository;
        this.studentPinCryptoService = studentPinCryptoService;
        this.examSessionRepository = examSessionRepository;
    }

    @TestConfiguration
    static class AuditTestProbeConfig {
        @Bean
        AuditTestProbeController auditTestProbeController() {
            return new AuditTestProbeController();
        }
    }

    @TestComponent
    @RestController
    @RequestMapping("/api/v1/admin/audit-test")
    static class AuditTestProbeController {

        @PatchMapping("/items/{id}")
        public ResponseEntity<Map<String, String>> patchItem(@PathVariable String id) {
            return ResponseEntity.ok(Map.of("patched", id));
        }

        @GetMapping("/exports/{slug}/credentials-pdf")
        public ResponseEntity<Map<String, String>> exportCredentialsPdfWithLongUri(@PathVariable String slug) {
            return ResponseEntity.ok(Map.of("slugLength", String.valueOf(slug.length())));
        }

        @PostMapping("/items/{slug}/save")
        public ResponseEntity<Map<String, String>> postLongItem(@PathVariable String slug) {
            return ResponseEntity.ok(Map.of("slugLength", String.valueOf(slug.length())));
        }
    }

    @AfterEach
    void cleanup() {
        for (String adminIdentifier : createdAdminIdentifiers) {
            jdbcTemplate.update("DELETE FROM audit_logs WHERE username = ?", adminIdentifier);
        }
        for (UUID sessionId : createdSessionIds) {
            if (examSessionRepository.existsById(sessionId)) {
                examSessionRepository.deleteById(sessionId);
            }
        }
        for (UUID studentId : createdStudentIds) {
            if (studentRepository.existsById(studentId)) {
                studentRepository.deleteById(studentId);
            }
        }
        for (UUID jobId : createdJobIds) {
            jdbcTemplate.update("DELETE FROM ingestion_jobs WHERE id = ?", jobId);
        }
        for (UUID subjectId : createdSubjectIds) {
            if (subjectRepository.existsById(subjectId)) {
                subjectRepository.deleteById(subjectId);
            }
        }
        for (String key : createdRedisKeys) {
            redisTemplate.delete(key);
        }

        createdAdminIdentifiers.clear();
        createdRedisKeys.clear();
        createdJobIds.clear();
        createdSubjectIds.clear();
        createdSessionIds.clear();
        createdStudentIds.clear();
    }

    private AdminIdentity issueAdminToken(String prefix) {
        String identifier = prefix + "_" + UUID.randomUUID().toString().substring(0, 8);
        String canonical = SessionRedisKeys.canonicalIdentifier(identifier);
        String jti = UUID.randomUUID().toString();
        String redisKey = SessionRedisKeys.sessionKey(canonical);
        redisTemplate.opsForValue().set(redisKey, jti, Duration.ofMinutes(15));
        createdRedisKeys.add(redisKey);
        createdAdminIdentifiers.add(canonical);
        return new AdminIdentity(canonical, jwtUtil.generateToken(canonical, "ROLE_ADMIN", jti));
    }

    private List<Map<String, Object>> findAuditRows(String username) {
        return jdbcTemplate.queryForList(
                "SELECT username, action, endpoint FROM audit_logs WHERE username = ? ORDER BY id ASC",
                username);
    }

    @Test
    void routineAdminGetPolls_InsertZeroAuditRows() throws Exception {
        AdminIdentity admin = issueAdminToken("AUDIT_POLL");

        Subject subject = new Subject("POLL_SUBJ_" + UUID.randomUUID().toString().substring(0, 8));
        subject = subjectRepository.save(subject);
        createdSubjectIds.add(subject.getId());

        UUID jobId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO ingestion_jobs (id, subject_id, status, total_chunks, processed_chunks) "
                        + "VALUES (?, ?, 'PROCESSING', 4, 2)",
                jobId,
                subject.getId());
        createdJobIds.add(jobId);

        mockMvc.perform(get("/api/v1/admin/analytics/dashboard")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + admin.token()))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/admin/analytics/flagged-sessions")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + admin.token()))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/admin/analytics/live-sessions")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + admin.token()))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/admin/analytics/results")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + admin.token()))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/admin/subjects")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + admin.token()))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/admin/questions")
                        .param("status", "DRAFT")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + admin.token()))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/admin/students")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + admin.token()))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/admin/ingestion/jobs/" + jobId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + admin.token()))
                .andExpect(status().isOk());

        assertThat(findAuditRows(admin.username())).isEmpty();
    }

    @Test
    void mutationsAndSensitiveExports_InsertSingleBoundedAuditRowPerRequest() throws Exception {
        AdminIdentity admin = issueAdminToken("AUDIT_MUT");

        // 1. POST mutation
        String subjectName = "AUDIT_SUBJ_" + UUID.randomUUID().toString().substring(0, 8);
        MvcResult createResult = mockMvc.perform(post("/api/v1/admin/subjects")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + admin.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("name", subjectName))))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode createdData = objectMapper.readTree(createResult.getResponse().getContentAsString()).path("data");
        UUID subjectId = UUID.fromString(createdData.path("id").asText());
        createdSubjectIds.add(subjectId);

        // 2. PUT mutation
        mockMvc.perform(put("/api/v1/admin/subjects/" + subjectId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + admin.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("name", subjectName + "_UPD"))))
                .andExpect(status().isOk());

        // 3. PATCH mutation
        mockMvc.perform(patch("/api/v1/admin/audit-test/items/item-42")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + admin.token()))
                .andExpect(status().isOk());

        // 4. DELETE mutation
        mockMvc.perform(delete("/api/v1/admin/subjects/" + subjectId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + admin.token()))
                .andExpect(status().isOk());

        // 5. Sensitive GET /slip, /credentials/pdf, /script, /script/pdf
        Student student = new Student();
        student.setName("Audit Slip Candidate");
        student.setRegistrationNumber("AUD" + UUID.randomUUID().toString().substring(0, 6).toUpperCase());
        student.setPinHash("dummy_hash_" + UUID.randomUUID());
        student.setPinCiphertext(studentPinCryptoService.encrypt("12345"));
        student.setPinResetRequired(false);
        student = studentRepository.save(student);
        createdStudentIds.add(student.getId());

        ExamSession session = new ExamSession();
        session.setUserId(student.getId());
        session.setStartTime(Instant.now().minusSeconds(600));
        session.setExpectedEndTime(Instant.now().plusSeconds(3000));
        session.setEndTime(Instant.now().minusSeconds(30));
        session.setStatus(ExamSessionStatus.SUBMITTED);
        session.setStatePayload(Map.of("answers", Map.of("q1", "A")));
        session.setSnapshotPayload(Map.of(
                "contexts", Map.of(),
                "questions", List.of(Map.of(
                        "id", "q1",
                        "topic", "Arithmetic",
                        "subject", Map.of("id", "sub-1", "name", "MATHEMATICS"),
                        "content", Map.of(
                                "text", "1 + 1 = ?",
                                "options", Map.of("A", "2", "B", "3"),
                                "correctOption", "A")))));
        session = examSessionRepository.save(session);
        createdSessionIds.add(session.getId());

        mockMvc.perform(get("/api/v1/admin/students/" + student.getId() + "/slip")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + admin.token()))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/admin/students/credentials/pdf")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + admin.token()))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/admin/exams/sessions/" + session.getId() + "/script")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + admin.token()))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/admin/exams/sessions/" + session.getId() + "/script/pdf")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + admin.token()))
                .andExpect(status().isOk());

        // 6. Long (> 255 chars) sensitive /credentials-pdf GET and POST endpoints truncate safely without SQL overflow
        String longSlug = "s".repeat(260);
        String longGetUri = "/api/v1/admin/audit-test/exports/" + longSlug + "/credentials-pdf";
        String longPostUri = "/api/v1/admin/audit-test/items/" + longSlug + "/save";
        assertThat(longGetUri.length()).isGreaterThan(255);
        assertThat(longPostUri.length()).isGreaterThan(255);

        mockMvc.perform(get(longGetUri)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + admin.token()))
                .andExpect(status().isOk());

        mockMvc.perform(post(longPostUri)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + admin.token()))
                .andExpect(status().isOk());

        List<Map<String, Object>> rows = findAuditRows(admin.username());
        assertThat(rows).hasSize(10);
        assertThat(rows).allSatisfy(row -> {
            String endpoint = (String) row.get("endpoint");
            assertThat(endpoint).isNotNull();
            assertThat(endpoint.length()).isLessThanOrEqualTo(255);
        });

        assertThat(rows.get(0).get("endpoint")).isEqualTo("/api/v1/admin/subjects");
        assertThat(rows.get(1).get("endpoint")).isEqualTo("/api/v1/admin/subjects/" + subjectId);
        assertThat(rows.get(2).get("endpoint")).isEqualTo("/api/v1/admin/audit-test/items/item-42");
        assertThat(rows.get(2).get("action")).isEqualTo("patchItem");
        assertThat(rows.get(3).get("endpoint")).isEqualTo("/api/v1/admin/subjects/" + subjectId);
        assertThat(rows.get(4).get("endpoint")).isEqualTo("/api/v1/admin/students/" + student.getId() + "/slip");
        assertThat(rows.get(5).get("endpoint")).isEqualTo("/api/v1/admin/students/credentials/pdf");
        assertThat(rows.get(6).get("endpoint")).isEqualTo("/api/v1/admin/exams/sessions/" + session.getId() + "/script");
        assertThat(rows.get(7).get("endpoint")).isEqualTo("/api/v1/admin/exams/sessions/" + session.getId() + "/script/pdf");
        assertThat(rows.get(8).get("endpoint")).isEqualTo(longGetUri.substring(0, 255));
        assertThat(rows.get(9).get("endpoint")).isEqualTo(longPostUri.substring(0, 255));
    }

    private record AdminIdentity(String username, String token) {
    }
}
