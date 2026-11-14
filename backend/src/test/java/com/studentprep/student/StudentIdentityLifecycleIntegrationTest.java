package com.studentprep.student;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import com.studentprep.common.SessionRedisKeys;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class StudentIdentityLifecycleIntegrationTest {

    private final MockMvc mockMvc;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbcTemplate;
    private final DataSource dataSource;
    private final PasswordEncoder passwordEncoder;
    private final StudentRepository studentRepository;
    private final UserRepository userRepository;
    private final StringRedisTemplate redisTemplate;
    private final String adminBootstrapSecret;

    private final List<UUID> createdStudentIds = new ArrayList<>();
    private final List<String> createdIdentifiers = new ArrayList<>();

    StudentIdentityLifecycleIntegrationTest(
            MockMvc mockMvc,
            ObjectMapper objectMapper,
            JdbcTemplate jdbcTemplate,
            DataSource dataSource,
            PasswordEncoder passwordEncoder,
            StudentRepository studentRepository,
            UserRepository userRepository,
            StringRedisTemplate redisTemplate,
            @Value("${security.bootstrap.admin-secret}") String adminBootstrapSecret) {
        this.mockMvc = mockMvc;
        this.objectMapper = objectMapper;
        this.jdbcTemplate = jdbcTemplate;
        this.dataSource = dataSource;
        this.passwordEncoder = passwordEncoder;
        this.studentRepository = studentRepository;
        this.userRepository = userRepository;
        this.redisTemplate = redisTemplate;
        this.adminBootstrapSecret = adminBootstrapSecret;
    }

    @BeforeEach
    void clearSharedRedisState() {
        redisTemplate.delete(List.of(
                SessionRedisKeys.sessionKey("ADMIN-001"),
                SessionRedisKeys.examUnlockKey("ADMIN-001"),
                SessionRedisKeys.loginFailIdKey("ADMIN-001"),
                SessionRedisKeys.loginIpKey("127.0.0.1"),
                SessionRedisKeys.refreshIpKey("127.0.0.1")));
    }

    @AfterEach
    void cleanupTestRecords() {
        clearSharedRedisState();
        for (UUID studentId : createdStudentIds) {
            studentRepository.findById(studentId).ifPresent(studentRepository::delete);
            userRepository.findById(studentId).ifPresent(userRepository::delete);
        }
        for (String identifier : createdIdentifiers) {
            userRepository.findByIdentifierIgnoreCase(identifier).ifPresent(userRepository::delete);
            redisTemplate.delete(SessionRedisKeys.sessionKey(identifier));
            redisTemplate.delete(SessionRedisKeys.examUnlockKey(identifier));
            redisTemplate.delete(SessionRedisKeys.loginFailIdKey(identifier));
        }
        createdStudentIds.clear();
        createdIdentifiers.clear();
    }

    @Test
    void flywaySequentialV2V16V18AndAdminBootstrap_HardensLegacyPinsAndAdmin001() {
        String tempSchema = "v18_verify_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        try {
            jdbcTemplate.execute("CREATE SCHEMA " + tempSchema);

            Flyway v16Flyway = Flyway.configure()
                    .dataSource(dataSource)
                    .schemas(tempSchema)
                    .locations("classpath:db/migration")
                    .target("16")
                    .load();
            v16Flyway.migrate();

            jdbcTemplate.update(
                    "INSERT INTO " + tempSchema + ".students (id, name, state, exam_center, registration_number, pin) "
                            + "VALUES (?, ?, ?, ?, ?, ?)",
                    UUID.fromString("aaaaaaaa-1111-1111-1111-111111111111"),
                    "Legacy Student",
                    "Lagos",
                    "Center 1",
                    "JAMB-2026-LEG001",
                    "12345");
            jdbcTemplate.update(
                    "INSERT INTO " + tempSchema + ".users (id, identifier, pin, role) VALUES (?, ?, ?, ?)",
                    UUID.fromString("aaaaaaaa-1111-1111-1111-111111111111"),
                    "JAMB-2026-LEG001",
                    "12345",
                    "ROLE_STUDENT");

            Flyway v18Flyway = Flyway.configure()
                    .dataSource(dataSource)
                    .schemas(tempSchema)
                    .locations("classpath:db/migration")
                    .load();
            v18Flyway.migrate();

            Map<String, Object> studentRow = jdbcTemplate.queryForMap(
                    "SELECT pin_hash, pin_reset_required FROM " + tempSchema + ".students WHERE registration_number = ?",
                    "JAMB-2026-LEG001");
            assertThat(studentRow.get("pin_hash")).isEqualTo("!LEGACY_INVALIDATED!");
            assertThat(studentRow.get("pin_reset_required")).isEqualTo(Boolean.TRUE);

            Map<String, Object> adminRow = jdbcTemplate.queryForMap(
                    "SELECT pin_hash, pin_reset_required FROM " + tempSchema + ".users WHERE identifier = ?",
                    "ADMIN-001");
            assertThat(adminRow.get("pin_hash")).isEqualTo("!ADMIN_BOOTSTRAP_REQUIRED!");
            assertThat(adminRow.get("pin_reset_required")).isEqualTo(Boolean.FALSE);
        } finally {
            jdbcTemplate.execute("DROP SCHEMA IF EXISTS " + tempSchema + " CASCADE");
        }

        User liveAdmin = userRepository.findByIdentifierIgnoreCase("ADMIN-001").orElseThrow();
        assertThat(liveAdmin.getPinHash()).startsWith("$argon2id$");
        assertThat(passwordEncoder.matches(adminBootstrapSecret, liveAdmin.getPinHash())).isTrue();
    }

    @Test
    void studentLifecycle_StoresArgon2idSynchronizesUserIdsProtectsDirectoryExportSlipAndInvalidatesImmediately()
            throws Exception {
        String adminToken = loginAndGetAccessToken("ADMIN-001", adminBootstrapSecret);

        MvcResult createResult = mockMvc.perform(post("/api/v1/admin/students")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"=Ada Lovelace\",\"examCenter\":\"Lagos Center\",\"subjectIds\":[]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.id").isNotEmpty())
                .andExpect(jsonPath("$.data.registrationNumber").isNotEmpty())
                .andExpect(jsonPath("$.data.oneTimePin").isNotEmpty())
                .andReturn();

        JsonNode createData = objectMapper.readTree(createResult.getResponse().getContentAsString()).path("data");
        UUID studentId = UUID.fromString(createData.path("id").asText());
        String regNumber = createData.path("registrationNumber").asText();
        String oneTimePin = createData.path("oneTimePin").asText();
        createdStudentIds.add(studentId);
        createdIdentifiers.add(regNumber);

        assertThat(oneTimePin).matches("^\\d{5}$");

        Student persistedStudent = studentRepository.findById(studentId).orElseThrow();
        assertThat(persistedStudent.getPinHash()).startsWith("$argon2id$");
        assertThat(persistedStudent.getPinHash()).doesNotContain(oneTimePin);
        assertThat(passwordEncoder.matches(oneTimePin, persistedStudent.getPinHash())).isTrue();
        assertThat(persistedStudent.isPinResetRequired()).isFalse();

        User syncedUser = waitForUserSync(studentId);
        assertThat(syncedUser.getId()).isEqualTo(persistedStudent.getId());
        assertThat(syncedUser.getIdentifier()).isEqualTo(regNumber);
        assertThat(syncedUser.getPinHash()).isEqualTo(persistedStudent.getPinHash());
        assertThat(syncedUser.isPinResetRequired()).isFalse();

        List<String> outboxPayloads = jdbcTemplate.queryForList(
                "SELECT serialized_event FROM event_publication WHERE serialized_event LIKE ?",
                String.class,
                "%" + regNumber + "%");
        assertThat(outboxPayloads).isNotEmpty();
        assertThat(outboxPayloads).allSatisfy(payload -> assertThat(payload).doesNotContain(oneTimePin));

        // Candidate logs in cleanly with oneTimePin
        String studentToken = loginAndGetAccessToken(regNumber, oneTimePin);
        mockMvc.perform(get("/api/v1/subjects")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + studentToken))
                .andExpect(status().isOk());

        // Bulk CSV import emits oneTimePin once and stores only Argon2id hash
        String bulkCsv = "Name,Exam Center,Subject 1,Subject 2,Subject 3,Subject 4\n"
                + "Katherine Johnson,Abuja Center,Biology,,,\n";
        MockMultipartFile bulkFile = new MockMultipartFile(
                "csvFile",
                "cohort.csv",
                "text/csv",
                bulkCsv.getBytes(StandardCharsets.UTF_8));
        MvcResult bulkResult = mockMvc.perform(multipart("/api/v1/admin/students/bulk")
                        .file(bulkFile)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.created").value(1))
                .andExpect(jsonPath("$.data.credentials[0].name").value("Katherine Johnson"))
                .andExpect(jsonPath("$.data.credentials[0].oneTimePin").isNotEmpty())
                .andReturn();
        JsonNode bulkData = objectMapper.readTree(bulkResult.getResponse().getContentAsString()).path("data");
        String bulkRegNum = bulkData.path("credentials").get(0).path("registrationNumber").asText();
        String bulkOneTimePin = bulkData.path("credentials").get(0).path("oneTimePin").asText();
        createdIdentifiers.add(bulkRegNum);
        Student bulkStudent = studentRepository.findByRegistrationNumberIgnoreCase(bulkRegNum).orElseThrow();
        createdStudentIds.add(bulkStudent.getId());
        assertThat(bulkStudent.getPinHash()).startsWith("$argon2id$");
        assertThat(bulkStudent.getPinHash()).doesNotContain(bulkOneTimePin);

        // Directory and detail endpoints expose zero credentials
        MvcResult listResult = mockMvc.perform(get("/api/v1/admin/students")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn();
        String listJson = listResult.getResponse().getContentAsString();
        assertThat(listJson).doesNotContain("\"pin\"");
        assertThat(listJson).doesNotContain("pinHash");
        assertThat(listJson).doesNotContain(oneTimePin);

        MvcResult detailResult = mockMvc.perform(get("/api/v1/admin/students/" + studentId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(studentId.toString()))
                .andExpect(jsonPath("$.data.hasActiveSession").value(true))
                .andReturn();
        String detailJson = detailResult.getResponse().getContentAsString();
        assertThat(detailJson).doesNotContain("\"pin\"");
        assertThat(detailJson).doesNotContain("pinHash");
        assertThat(detailJson).doesNotContain(oneTimePin);

        // CSV export omits Login PIN and neutralizes '=Ada Lovelace'
        MvcResult exportResult = mockMvc.perform(get("/api/v1/admin/students/export")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn();
        String exportedCsv = exportResult.getResponse().getContentAsString();
        assertThat(exportedCsv).startsWith("Name,Registration Number,Exam Center,Registered Subjects");
        assertThat(exportedCsv).doesNotContain("Login PIN");
        assertThat(exportedCsv).contains("\"'=Ada Lovelace\"");
        assertThat(exportedCsv).doesNotContain(oneTimePin);

        // Slip PDF contains redacted notice and zero plaintext PIN/hash
        MvcResult slipResult = mockMvc.perform(get("/api/v1/admin/students/" + studentId + "/slip")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn();
        String slipText;
        try (PdfReader reader = new PdfReader(slipResult.getResponse().getContentAsByteArray())) {
            slipText = new PdfTextExtractor(reader).getTextFromPage(1);
        }
        assertThat(slipText).contains("Login PIN: Issued separately (hashed)");
        assertThat(slipText).doesNotContain(oneTimePin);
        assertThat(slipText).doesNotContain("$argon2id$");

        // PIN reset immediately invalidates old PIN & active JWT (zero outbox race window)
        MvcResult resetResult = mockMvc.perform(post("/api/v1/admin/students/" + studentId + "/reset-pin")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.newPin").isNotEmpty())
                .andExpect(jsonPath("$.data.registrationNumber").value(regNumber))
                .andReturn();
        String newPin = objectMapper.readTree(resetResult.getResponse().getContentAsString())
                .path("data").path("newPin").asText();
        assertThat(newPin).matches("^\\d{5}$");

        mockMvc.perform(get("/api/v1/subjects")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + studentToken))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("identifier", regNumber, "pin", oneTimePin))))
                .andExpect(status().isUnauthorized());

        String refreshedStudentToken = loginAndGetAccessToken(regNumber, newPin);

        // Delete immediately invalidates both token and future login (zero outbox race window)
        mockMvc.perform(delete("/api/v1/admin/students/" + studentId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/subjects")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + refreshedStudentToken))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("identifier", regNumber, "pin", newPin))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void legacyInvalidatedAccount_Returns403PinResetRequiredOnLoginAndSupervisorBlockedFromMutationsAndExport()
            throws Exception {
        String supervisorId = "SUP-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        User supervisor = new User();
        supervisor.setId(UUID.randomUUID());
        supervisor.setIdentifier(supervisorId);
        supervisor.setPinHash(passwordEncoder.encode("sup123456"));
        supervisor.setRole("ROLE_SUPERVISOR");
        supervisor.setPinResetRequired(false);
        userRepository.save(supervisor);
        createdIdentifiers.add(supervisorId);

        Student legacyStudent = new Student();
        legacyStudent.setName("Legacy Candidate");
        legacyStudent.setRegistrationNumber("JAMB-2026-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase());
        legacyStudent.setExamCenter("Center Z");
        legacyStudent.setPinHash("!LEGACY_INVALIDATED!");
        legacyStudent.setPinResetRequired(true);
        legacyStudent = studentRepository.save(legacyStudent);
        createdStudentIds.add(legacyStudent.getId());
        createdIdentifiers.add(legacyStudent.getRegistrationNumber());

        User legacyUser = new User();
        legacyUser.setId(legacyStudent.getId());
        legacyUser.setIdentifier(legacyStudent.getRegistrationNumber());
        legacyUser.setPinHash("!LEGACY_INVALIDATED!");
        legacyUser.setRole("ROLE_STUDENT");
        legacyUser.setPinResetRequired(true);
        userRepository.save(legacyUser);

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("identifier", legacyStudent.getRegistrationNumber(), "pin", "12345"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PIN_RESET_REQUIRED"));

        String supervisorToken = loginAndGetAccessToken(supervisorId, "sup123456");

        mockMvc.perform(get("/api/v1/admin/students")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + supervisorToken))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/admin/students/" + legacyStudent.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + supervisorToken))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/admin/students/export")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + supervisorToken))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/v1/admin/students/" + legacyStudent.getId() + "/slip")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + supervisorToken))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/admin/students")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + supervisorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Blocked\",\"examCenter\":\"Center 1\",\"subjectIds\":[]}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(put("/api/v1/admin/students/" + legacyStudent.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + supervisorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Blocked\",\"examCenter\":\"Center 1\",\"subjectIds\":[]}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/admin/students/" + legacyStudent.getId() + "/reset-pin")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + supervisorToken))
                .andExpect(status().isForbidden());

        mockMvc.perform(delete("/api/v1/admin/students/" + legacyStudent.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + supervisorToken))
                .andExpect(status().isForbidden());
    }

    private String loginAndGetAccessToken(String identifier, String pin) throws Exception {
        MvcResult loginResult = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("identifier", identifier, "pin", pin))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(loginResult.getResponse().getContentAsString())
                .path("data")
                .path("accessToken")
                .asText();
    }

    private User waitForUserSync(UUID studentId) throws InterruptedException {
        long deadlineNanos = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadlineNanos) {
            Optional<User> found = userRepository.findById(studentId);
            if (found.isPresent()) {
                return found.get();
            }
            Thread.sleep(50);
        }
        return userRepository.findById(studentId)
                .orElseThrow(() -> new AssertionError("Timed out waiting for UserSyncListener for student " + studentId));
    }
}
