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
import org.springframework.test.web.servlet.ResultActions;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
            jdbcTemplate.update("DELETE FROM audit_logs WHERE endpoint LIKE ?", "%" + studentId + "%");
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
    void flywaySequentialV2V16V18V19AndAdminBootstrap_HardensLegacyPinsAddsCiphertextColumnAndBootstrapsAdmin001() {
        String tempSchema = "v19_verify_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
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

            Flyway latestFlyway = Flyway.configure()
                    .dataSource(dataSource)
                    .schemas(tempSchema)
                    .locations("classpath:db/migration")
                    .load();
            latestFlyway.migrate();

            Map<String, Object> studentRow = jdbcTemplate.queryForMap(
                    "SELECT registration_number, pin_hash, pin_reset_required, pin_ciphertext FROM " + tempSchema
                            + ".students WHERE id = ?",
                    UUID.fromString("aaaaaaaa-1111-1111-1111-111111111111"));
            String convertedRegNumber = (String) studentRow.get("registration_number");
            assertThat(convertedRegNumber).isNotEqualTo("JAMB-2026-LEG001");
            assertThat(convertedRegNumber).matches("^\\d{6}[A-Z]{2}$");
            assertThat(studentRow.get("pin_hash")).isEqualTo("!LEGACY_INVALIDATED!");
            assertThat(studentRow.get("pin_reset_required")).isEqualTo(Boolean.TRUE);
            assertThat(studentRow.get("pin_ciphertext")).isNull();

            String convertedUserIdentifier = jdbcTemplate.queryForObject(
                    "SELECT identifier FROM " + tempSchema + ".users WHERE id = ?",
                    String.class,
                    UUID.fromString("aaaaaaaa-1111-1111-1111-111111111111"));
            assertThat(convertedUserIdentifier).isEqualTo(convertedRegNumber);

            Integer examCenterColumnCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.columns "
                            + "WHERE table_schema = ? AND table_name = 'students' AND column_name = 'exam_center'",
                    Integer.class,
                    tempSchema);
            assertThat(examCenterColumnCount).isZero();

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
    void studentLifecycle_StoresEncryptedAndHashedCredentialsSupportsBulkAndSinglePdfsProtectsEndpointsAndAuditLogs()
            throws Exception {
        String adminToken = loginAndGetAccessToken("ADMIN-001", adminBootstrapSecret);

        MvcResult createResult = mockMvc.perform(post("/api/v1/admin/students")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"=Ada Lovelace\",\"subjectIds\":[]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.id").isNotEmpty())
                .andExpect(jsonPath("$.data.registrationNumber").isNotEmpty())
                .andExpect(jsonPath("$.data.oneTimePin").isNotEmpty())
                .andReturn();

        String createJson = createResult.getResponse().getContentAsString();
        assertThat(createJson).doesNotContain("examCenter");
        JsonNode createData = objectMapper.readTree(createJson).path("data");
        UUID studentId = UUID.fromString(createData.path("id").asText());
        String regNumber = createData.path("registrationNumber").asText();
        String oneTimePin = createData.path("oneTimePin").asText();
        createdStudentIds.add(studentId);
        createdIdentifiers.add(regNumber);

        assertThat(regNumber).matches("^\\d{6}[A-Z]{2}$");
        assertThat(oneTimePin).matches("^\\d{5}$");

        Student persistedStudent = studentRepository.findById(studentId).orElseThrow();
        assertThat(persistedStudent.getPinHash()).startsWith("$argon2id$");
        assertThat(persistedStudent.getPinHash()).doesNotContain(oneTimePin);
        assertThat(passwordEncoder.matches(oneTimePin, persistedStudent.getPinHash())).isTrue();
        assertThat(persistedStudent.getPinCiphertext()).startsWith("v1:");
        assertThat(persistedStudent.getPinCiphertext()).doesNotContain(oneTimePin);
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
        assertThat(outboxPayloads).allSatisfy(payload -> {
            assertThat(payload).doesNotContain(oneTimePin);
            assertThat(payload).doesNotContain(persistedStudent.getPinCiphertext());
        });

        // Candidate logs in cleanly with oneTimePin and cannot invoke admin PDF endpoints
        String studentToken = loginAndGetAccessToken(regNumber, oneTimePin);
        mockMvc.perform(get("/api/v1/subjects")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + studentToken))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/admin/students/credentials/pdf")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + studentToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/admin/students/credentials/pdf")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + studentToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("registrationNumbers", List.of(regNumber)))))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/admin/students/" + studentId + "/slip")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + studentToken))
                .andExpect(status().isForbidden());

        // Bulk CSV import emits oneTimePin once and stores both Argon2id hash and AES-GCM ciphertext
        String bulkCsv = "Name,Subject 1,Subject 2,Subject 3,Subject 4\n"
                + "Katherine Johnson,Biology,,,\n";
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
        assertThat(bulkRegNum).matches("^\\d{6}[A-Z]{2}$");
        createdIdentifiers.add(bulkRegNum);
        Student bulkStudent = studentRepository.findByRegistrationNumberIgnoreCase(bulkRegNum).orElseThrow();
        createdStudentIds.add(bulkStudent.getId());
        assertThat(bulkStudent.getPinHash()).startsWith("$argon2id$");
        assertThat(bulkStudent.getPinHash()).doesNotContain(bulkOneTimePin);
        assertThat(bulkStudent.getPinCiphertext()).startsWith("v1:");
        assertThat(bulkStudent.getPinCiphertext()).doesNotContain(bulkOneTimePin);

        // Directory and detail endpoints expose zero credentials or ciphertexts
        MvcResult listResult = mockMvc.perform(get("/api/v1/admin/students")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn();
        String listJson = listResult.getResponse().getContentAsString();
        assertThat(listJson).doesNotContain("examCenter");
        assertThat(listJson).doesNotContain("\"pin\"");
        assertThat(listJson).doesNotContain("pinHash");
        assertThat(listJson).doesNotContain("pinCiphertext");
        assertThat(listJson).doesNotContain("pin_ciphertext");
        assertThat(listJson).doesNotContain(oneTimePin);
        assertThat(listJson).doesNotContain(bulkOneTimePin);
        assertThat(listJson).doesNotContain(persistedStudent.getPinCiphertext());

        MvcResult detailResult = mockMvc.perform(get("/api/v1/admin/students/" + studentId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(studentId.toString()))
                .andExpect(jsonPath("$.data.hasActiveSession").value(true))
                .andReturn();
        String detailJson = detailResult.getResponse().getContentAsString();
        assertThat(detailJson).doesNotContain("examCenter");
        assertThat(detailJson).doesNotContain("\"pin\"");
        assertThat(detailJson).doesNotContain("pinHash");
        assertThat(detailJson).doesNotContain("pinCiphertext");
        assertThat(detailJson).doesNotContain("pin_ciphertext");
        assertThat(detailJson).doesNotContain(oneTimePin);
        assertThat(detailJson).doesNotContain(persistedStudent.getPinCiphertext());

        // CSV export omits Login PIN and ciphertexts, and neutralizes '=Ada Lovelace'
        MvcResult exportResult = mockMvc.perform(get("/api/v1/admin/students/export")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn();
        String exportedCsv = exportResult.getResponse().getContentAsString();
        assertThat(exportedCsv).startsWith("Name,Registration Number,Registered Subjects");
        assertThat(exportedCsv).doesNotContain("Exam Center");
        assertThat(exportedCsv).doesNotContain("Login PIN");
        assertThat(exportedCsv).contains("\"'=Ada Lovelace\"");
        assertThat(exportedCsv).doesNotContain(oneTimePin);
        assertThat(exportedCsv).doesNotContain(bulkOneTimePin);
        assertThat(exportedCsv).doesNotContain(persistedStudent.getPinCiphertext());
        assertThat(exportedCsv).doesNotContain(bulkStudent.getPinCiphertext());

        // Single-student slip PDF prints active oneTimePin without rotating it across repeat downloads
        String firstSlipText = downloadAndExtractPdfText(
                mockMvc.perform(get("/api/v1/admin/students/" + studentId + "/slip")
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)),
                "slip.pdf");
        assertThat(firstSlipText).contains("Login PIN: " + oneTimePin);
        assertThat(firstSlipText).contains(regNumber);
        assertThat(firstSlipText).doesNotContain("Exam Center");
        assertThat(firstSlipText).doesNotContain("$argon2id$");
        assertThat(firstSlipText).doesNotContain(persistedStudent.getPinCiphertext());

        String repeatSlipText = downloadAndExtractPdfText(
                mockMvc.perform(get("/api/v1/admin/students/" + studentId + "/slip")
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)),
                "slip.pdf");
        assertThat(repeatSlipText).contains("Login PIN: " + oneTimePin);

        // Existing student session remains valid after repeat PDF download
        mockMvc.perform(get("/api/v1/subjects")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + studentToken))
                .andExpect(status().isOk());

        // Consolidated bulk PDF returns all students with active PINs and stays stable across repeats
        String firstBulkPdfText = downloadAndExtractPdfText(
                mockMvc.perform(get("/api/v1/admin/students/credentials/pdf")
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)),
                "student_credentials_roster.pdf");
        assertThat(firstBulkPdfText).contains(
                "Scope: All Registered Students",
                "Confidential: For authorized examination administrator distribution only.",
                regNumber,
                oneTimePin,
                bulkRegNum,
                bulkOneTimePin);
        assertThat(firstBulkPdfText).doesNotContain("Exam Center");
        assertThat(firstBulkPdfText).doesNotContain("$argon2id$");
        assertThat(firstBulkPdfText).doesNotContain(persistedStudent.getPinCiphertext());
        assertThat(firstBulkPdfText).doesNotContain(bulkStudent.getPinCiphertext());

        String secondBulkPdfText = downloadAndExtractPdfText(
                mockMvc.perform(get("/api/v1/admin/students/credentials/pdf")
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)),
                "student_credentials_roster.pdf");
        assertThat(secondBulkPdfText).contains(regNumber, oneTimePin, bulkRegNum, bulkOneTimePin);

        // Batch POST PDF filters strictly to requested registrationNumbers and validates payloads
        String batchPdfText = downloadAndExtractPdfText(
                mockMvc.perform(post("/api/v1/admin/students/credentials/pdf")
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(
                                        Map.of("registrationNumbers", List.of(bulkRegNum))))),
                "batch_credentials_roster.pdf");
        assertThat(batchPdfText).contains(bulkRegNum, bulkOneTimePin);
        assertThat(batchPdfText).doesNotContain(regNumber);
        assertThat(batchPdfText).doesNotContain("Exam Center");

        mockMvc.perform(post("/api/v1/admin/students/credentials/pdf")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"registrationNumbers\":[]}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/v1/admin/students/credentials/pdf")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"registrationNumbers\":[\"   \"]}"))
                .andExpect(status().isBadRequest());

        // Audit logs record credential PDF exports without leaking plaintext PINs or ciphertexts
        List<Map<String, Object>> pdfAuditRows = jdbcTemplate.queryForList(
                "SELECT username, action, endpoint FROM audit_logs WHERE endpoint IN (?, ?)",
                "/api/v1/admin/students/credentials/pdf",
                "/api/v1/admin/students/" + studentId + "/slip");
        assertThat(pdfAuditRows).hasSizeGreaterThanOrEqualTo(6);
        assertThat(pdfAuditRows).allSatisfy(row -> {
            assertThat(row.get("username")).isEqualTo("ADMIN-001");
            String serialized = row.toString();
            assertThat(serialized).doesNotContain(oneTimePin);
            assertThat(serialized).doesNotContain(bulkOneTimePin);
            assertThat(serialized).doesNotContain(persistedStudent.getPinCiphertext());
        });

        // PIN reset immediately invalidates old PIN & active JWT and updates both PDFs
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

        String resetSlipText = downloadAndExtractPdfText(
                mockMvc.perform(get("/api/v1/admin/students/" + studentId + "/slip")
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)),
                "slip.pdf");
        assertThat(resetSlipText).contains("Login PIN: " + newPin);
        if (!newPin.equals(oneTimePin)) {
            assertThat(resetSlipText).doesNotContain("Login PIN: " + oneTimePin);
        }

        String resetBulkPdfText = downloadAndExtractPdfText(
                mockMvc.perform(post("/api/v1/admin/students/credentials/pdf")
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(
                                        Map.of("registrationNumbers", List.of(regNumber, bulkRegNum))))),
                "batch_credentials_roster.pdf");
        assertThat(resetBulkPdfText).contains(regNumber, newPin, bulkRegNum, bulkOneTimePin);

        // Delete immediately invalidates both token and future login and omits deleted student from bulk PDF
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

        String postDeleteBulkPdfText = downloadAndExtractPdfText(
                mockMvc.perform(get("/api/v1/admin/students/credentials/pdf")
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)),
                "student_credentials_roster.pdf");
        assertThat(postDeleteBulkPdfText).doesNotContain(regNumber);
        assertThat(postDeleteBulkPdfText).contains(bulkRegNum, bulkOneTimePin);
    }

    @Test
    void legacyInvalidatedAccount_BlocksSupervisorFromAllPdfEndpointsAndLazilyActivatesOnceForAdminExportAndLogin()
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
        String legacyRegNum = "JAMB-2026-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        legacyStudent.setRegistrationNumber(legacyRegNum);
        legacyStudent.setPinHash("!LEGACY_INVALIDATED!");
        legacyStudent.setPinCiphertext(null);
        legacyStudent.setPinResetRequired(true);
        legacyStudent = studentRepository.save(legacyStudent);
        createdStudentIds.add(legacyStudent.getId());
        createdIdentifiers.add(legacyRegNum);

        User legacyUser = new User();
        legacyUser.setId(legacyStudent.getId());
        legacyUser.setIdentifier(legacyRegNum);
        legacyUser.setPinHash("!LEGACY_INVALIDATED!");
        legacyUser.setRole("ROLE_STUDENT");
        legacyUser.setPinResetRequired(true);
        userRepository.save(legacyUser);

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("identifier", legacyRegNum, "pin", "12345"))))
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

        mockMvc.perform(get("/api/v1/admin/students/credentials/pdf")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + supervisorToken))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/admin/students/credentials/pdf")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + supervisorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("registrationNumbers", List.of(legacyRegNum)))))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/v1/admin/students/" + legacyStudent.getId() + "/slip")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + supervisorToken))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/admin/students")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + supervisorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Blocked\",\"subjectIds\":[]}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(put("/api/v1/admin/students/" + legacyStudent.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + supervisorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Blocked\",\"subjectIds\":[]}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/admin/students/" + legacyStudent.getId() + "/reset-pin")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + supervisorToken))
                .andExpect(status().isForbidden());

        mockMvc.perform(delete("/api/v1/admin/students/" + legacyStudent.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + supervisorToken))
                .andExpect(status().isForbidden());

        // ROLE_ADMIN batch PDF export lazily backfills legacy student once and keeps PIN stable on repeat
        String adminToken = loginAndGetAccessToken("ADMIN-001", adminBootstrapSecret);
        String legacyBatchPdfText = downloadAndExtractPdfText(
                mockMvc.perform(post("/api/v1/admin/students/credentials/pdf")
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(
                                        Map.of("registrationNumbers", List.of(legacyRegNum))))),
                "batch_credentials_roster.pdf");

        String provisionedPin = extractProvisionedPinForCandidate(legacyBatchPdfText, legacyRegNum);
        assertThat(provisionedPin).matches("^\\d{5}$");

        Student reloadedLegacy = studentRepository.findById(legacyStudent.getId()).orElseThrow();
        assertThat(reloadedLegacy.isPinResetRequired()).isFalse();
        assertThat(reloadedLegacy.getPinCiphertext()).startsWith("v1:");
        assertThat(reloadedLegacy.getPinHash()).startsWith("$argon2id$");
        assertThat(passwordEncoder.matches(provisionedPin, reloadedLegacy.getPinHash())).isTrue();

        // Wait for outbox UserSyncListener to synchronize users.pin_hash and clear pin_reset_required
        waitForUserPinSync(legacyStudent.getId(), provisionedPin);

        // Repeat single-slip download keeps the exact same provisioned PIN
        String legacySlipText = downloadAndExtractPdfText(
                mockMvc.perform(get("/api/v1/admin/students/" + legacyStudent.getId() + "/slip")
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)),
                "slip.pdf");
        assertThat(legacySlipText).contains("Login PIN: " + provisionedPin);

        // Legacy candidate authenticates cleanly on /api/v1/auth/login with printed PIN
        String legacyStudentToken = loginAndGetAccessToken(legacyRegNum, provisionedPin);
        mockMvc.perform(get("/api/v1/subjects")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + legacyStudentToken))
                .andExpect(status().isOk());
    }

    private String downloadAndExtractPdfText(
            ResultActions actions,
            String expectedFilename) throws Exception {
        MvcResult result = actions
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, containsString(expectedFilename)))
                .andReturn();

        byte[] pdfBytes = result.getResponse().getContentAsByteArray();
        assertThat(pdfBytes).isNotEmpty();
        assertThat(new String(pdfBytes, 0, Math.min(5, pdfBytes.length), StandardCharsets.US_ASCII))
                .isEqualTo("%PDF-");

        try (PdfReader reader = new PdfReader(pdfBytes)) {
            PdfTextExtractor extractor = new PdfTextExtractor(reader);
            StringBuilder text = new StringBuilder();
            for (int page = 1; page <= reader.getNumberOfPages(); page++) {
                if (page > 1) {
                    text.append('\n');
                }
                text.append(extractor.getTextFromPage(page));
            }
            return text.toString();
        }
    }

    private static String extractProvisionedPinForCandidate(String pdfText, String regNum) {
        Pattern rowPattern = Pattern.compile(
                Pattern.quote(regNum) + "\\s*(\\d{5})\\b");
        Matcher matcher = rowPattern.matcher(pdfText);
        assertThat(matcher.find())
                .withFailMessage("Expected 5-digit PIN for %s in PDF text:\n%s", regNum, pdfText)
                .isTrue();
        return matcher.group(1);
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

    private void waitForUserPinSync(UUID studentId, String rawPin) throws InterruptedException {
        long deadlineNanos = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadlineNanos) {
            Optional<User> found = userRepository.findById(studentId);
            if (found.isPresent()
                    && !found.get().isPinResetRequired()
                    && passwordEncoder.matches(rawPin, found.get().getPinHash())) {
                return;
            }
            Thread.sleep(50);
        }
        User latest = userRepository.findById(studentId).orElseThrow();
        assertThat(latest.isPinResetRequired()).isFalse();
        assertThat(passwordEncoder.matches(rawPin, latest.getPinHash())).isTrue();
    }
}
