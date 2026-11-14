package com.studentprep.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.studentprep.common.SessionRedisKeys;
import com.studentprep.student.Student;
import com.studentprep.student.StudentRepository;
import com.studentprep.student.User;
import com.studentprep.student.UserRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class AuthSessionLifecycleIntegrationTest {

    private final MockMvc mockMvc;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbcTemplate;
    private final PasswordEncoder passwordEncoder;
    private final StudentRepository studentRepository;
    private final UserRepository userRepository;
    private final StringRedisTemplate redisTemplate;
    private final JwtUtil jwtUtil;

    private final List<UUID> createdStudentIds = new ArrayList<>();
    private final List<String> createdIdentifiers = new ArrayList<>();
    private final List<String> touchedIpKeys = new ArrayList<>();

    AuthSessionLifecycleIntegrationTest(
            MockMvc mockMvc,
            ObjectMapper objectMapper,
            JdbcTemplate jdbcTemplate,
            PasswordEncoder passwordEncoder,
            StudentRepository studentRepository,
            UserRepository userRepository,
            StringRedisTemplate redisTemplate,
            JwtUtil jwtUtil) {
        this.mockMvc = mockMvc;
        this.objectMapper = objectMapper;
        this.jdbcTemplate = jdbcTemplate;
        this.passwordEncoder = passwordEncoder;
        this.studentRepository = studentRepository;
        this.userRepository = userRepository;
        this.redisTemplate = redisTemplate;
        this.jwtUtil = jwtUtil;
    }

    @AfterEach
    void cleanup() {
        for (UUID studentId : createdStudentIds) {
            jdbcTemplate.update("DELETE FROM exam_sessions WHERE user_id = ?", studentId);
            jdbcTemplate.update("DELETE FROM audit_logs WHERE endpoint LIKE ?", "%" + studentId + "%");
            studentRepository.findById(studentId).ifPresent(studentRepository::delete);
            userRepository.findById(studentId).ifPresent(userRepository::delete);
        }
        for (String identifier : createdIdentifiers) {
            userRepository.findByIdentifierIgnoreCase(identifier).ifPresent(userRepository::delete);
            redisTemplate.delete(SessionRedisKeys.sessionKey(identifier));
            redisTemplate.delete(SessionRedisKeys.examUnlockKey(identifier));
            redisTemplate.delete(SessionRedisKeys.loginFailIdKey(identifier));
            redisTemplate.delete(SessionRedisKeys.refreshIdKey(identifier));
            redisTemplate.delete(SessionRedisKeys.syncKey(identifier));
        }
        for (String ipKey : touchedIpKeys) {
            redisTemplate.delete(ipKey);
        }
        createdStudentIds.clear();
        createdIdentifiers.clear();
        touchedIpKeys.clear();
    }

    @Test
    void sessionLifecycle_EnforcesSingleWorkstationUnlockEvictionRefreshAndLogoutOwnership() throws Exception {
        String regNumber = createCandidate("73914");
        String supervisorId = createStaff("ROLE_SUPERVISOR", "supSecret!23");
        String clientIp = "10.88.1.10";
        touchedIpKeys.add(SessionRedisKeys.loginIpKey(clientIp));
        touchedIpKeys.add(SessionRedisKeys.refreshIpKey(clientIp));

        // 1. Device A logs in (with lowercase identifier to confirm canonical key normalization)
        MvcResult loginAResult = mockMvc.perform(post("/api/v1/auth/login")
                        .with(req -> {
                            req.setRemoteAddr(clientIp);
                            return req;
                        })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("identifier", regNumber.toLowerCase(), "pin", "73914"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.data.expiresIn").value(900))
                .andExpect(jsonPath("$.data.role").value("ROLE_STUDENT"))
                .andReturn();

        String setCookieHeader = loginAResult.getResponse().getHeader(HttpHeaders.SET_COOKIE);
        assertThat(setCookieHeader).isNotNull();
        assertThat(setCookieHeader).contains("HttpOnly");
        assertThat(setCookieHeader).contains("Path=/api/v1/auth");
        assertThat(setCookieHeader).contains("SameSite=Strict");

        Cookie refreshCookieA = loginAResult.getResponse().getCookie("refreshToken");
        assertThat(refreshCookieA).isNotNull();
        String accessTokenA = objectMapper.readTree(loginAResult.getResponse().getContentAsString())
                .path("data").path("accessToken").asText();
        assertThat(jwtUtil.extractTokenType(accessTokenA)).isEqualTo(JwtUtil.TOKEN_TYPE_ACCESS);
        assertThat(jwtUtil.extractTokenType(refreshCookieA.getValue())).isEqualTo(JwtUtil.TOKEN_TYPE_REFRESH);

        // 2. Concurrent login from Device B (no cookie, no unlock) -> 409 Conflict
        mockMvc.perform(post("/api/v1/auth/login")
                        .with(req -> {
                            req.setRemoteAddr(clientIp);
                            return req;
                        })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("identifier", regNumber, "pin", "73914"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONCURRENT_SESSION_CONFLICT"));

        // 3. Same workstation recovery (Device A refresh cookie) succeeds cleanly
        MvcResult sameDeviceResult = mockMvc.perform(post("/api/v1/auth/login")
                        .with(req -> {
                            req.setRemoteAddr(clientIp);
                            return req;
                        })
                        .cookie(refreshCookieA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("identifier", regNumber, "pin", "73914"))))
                .andExpect(status().isOk())
                .andReturn();
        String activeAccessTokenA2 = objectMapper.readTree(sameDeviceResult.getResponse().getContentAsString())
                .path("data").path("accessToken").asText();
        Cookie activeRefreshCookieA2 = sameDeviceResult.getResponse().getCookie("refreshToken");
        assertThat(activeRefreshCookieA2).isNotNull();

        // Superseded Device A token immediately receives 401 SESSION_EVICTED
        mockMvc.perform(get("/api/v1/subjects")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessTokenA))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("SESSION_EVICTED"))
                .andExpect(jsonPath("$.detail").value("Session active on another device"));

        // 4. Bind active IN_PROGRESS exam -> Device B is blocked with 409 ACTIVE_EXAM_LOCKED
        Student candidate = studentRepository.findByRegistrationNumberIgnoreCase(regNumber).orElseThrow();
        jdbcTemplate.update(
                "INSERT INTO exam_sessions (id, user_id, status, created_at) VALUES (?, ?, 'IN_PROGRESS', NOW())",
                UUID.randomUUID(),
                candidate.getId());

        mockMvc.perform(post("/api/v1/auth/login")
                        .with(req -> {
                            req.setRemoteAddr(clientIp);
                            return req;
                        })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("identifier", regNumber, "pin", "73914"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ACTIVE_EXAM_LOCKED"));

        // Supervisor cannot call unlock-session -> 403 Forbidden
        String supervisorToken = loginAs("10.88.1.11", supervisorId, "supSecret!23");
        mockMvc.perform(post("/api/v1/admin/students/" + candidate.getId() + "/unlock-session")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + supervisorToken))
                .andExpect(status().isForbidden());

        // 5. Admin calls unlock-session -> clears session, sets 15m exam_unlock permit, writes AuditLog
        String adminId = createStaff("ROLE_ADMIN", "adminSecret!23");
        String adminToken = loginAs("10.88.1.12", adminId, "adminSecret!23");
        mockMvc.perform(post("/api/v1/admin/students/" + candidate.getId() + "/unlock-session")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.studentId").value(candidate.getId().toString()))
                .andExpect(jsonPath("$.data.registrationNumber").value(regNumber))
                .andExpect(jsonPath("$.data.unlocked").value(true));

        assertThat(redisTemplate.opsForValue().get(SessionRedisKeys.sessionKey(regNumber))).isNull();
        assertThat(redisTemplate.opsForValue().get(SessionRedisKeys.examUnlockKey(regNumber))).isNotNull();

        Integer auditCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_logs WHERE endpoint = ?",
                Integer.class,
                "/api/v1/admin/students/" + candidate.getId() + "/unlock-session");
        assertThat(auditCount).isNotNull().isGreaterThanOrEqualTo(1);

        // 6. Device B logs in once using the unlock permit -> 200 OK
        MvcResult deviceBResult = mockMvc.perform(post("/api/v1/auth/login")
                        .with(req -> {
                            req.setRemoteAddr(clientIp);
                            return req;
                        })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("identifier", regNumber, "pin", "73914"))))
                .andExpect(status().isOk())
                .andReturn();
        String accessTokenB = objectMapper.readTree(deviceBResult.getResponse().getContentAsString())
                .path("data").path("accessToken").asText();
        Cookie refreshCookieB = deviceBResult.getResponse().getCookie("refreshToken");
        assertThat(refreshCookieB).isNotNull();
        assertThat(redisTemplate.opsForValue().get(SessionRedisKeys.examUnlockKey(regNumber))).isNull();

        // Single-use guarantee: Device C cannot reuse consumed permit -> 409
        mockMvc.perform(post("/api/v1/auth/login")
                        .with(req -> {
                            req.setRemoteAddr(clientIp);
                            return req;
                        })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("identifier", regNumber, "pin", "73914"))))
                .andExpect(status().isConflict());

        // 7. Refresh checks: access token in refresh cookie rejected (401), valid refresh cookie succeeds (200)
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .with(req -> {
                            req.setRemoteAddr(clientIp);
                            return req;
                        })
                        .cookie(new Cookie("refreshToken", accessTokenB)))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .with(req -> {
                            req.setRemoteAddr(clientIp);
                            return req;
                        })
                        .cookie(refreshCookieB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty());

        // Refresh token sent as Bearer token on protected route is rejected (401)
        mockMvc.perform(get("/api/v1/subjects")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + refreshCookieB.getValue()))
                .andExpect(status().isUnauthorized());

        // 8. Evicted workstation A calling /logout MUST NOT terminate Device B's active session!
        mockMvc.perform(post("/api/v1/auth/logout")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + activeAccessTokenA2)
                        .cookie(activeRefreshCookieA2))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("Max-Age=0")));

        // Device B still works!
        mockMvc.perform(get("/api/v1/subjects")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessTokenB))
                .andExpect(status().isOk());

        // 9. Legitimate Device B logout with signature-verified EXPIRED access token revokes Redis session
        JwtUtil expiredJwtUtil = new JwtUtil(
                "test_only_jwt_secret_key_at_least_32_bytes_long_12345",
                -60_000L,
                604_800_000L);
        String jtiB = jwtUtil.extractJti(accessTokenB);
        String expiredValidAccessB = expiredJwtUtil.generateToken(regNumber, "ROLE_STUDENT", jtiB);

        mockMvc.perform(post("/api/v1/auth/logout")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + expiredValidAccessB))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("Path=/api/v1/auth")));

        assertThat(redisTemplate.opsForValue().get(SessionRedisKeys.sessionKey(regNumber))).isNull();
    }

    @Test
    void refreshRejectsFlaggedMalpracticeAndDeletedStudentAccounts() throws Exception {
        String regNumber = createCandidate("49281");
        String clientIp = "10.88.1.20";
        touchedIpKeys.add(SessionRedisKeys.loginIpKey(clientIp));
        touchedIpKeys.add(SessionRedisKeys.refreshIpKey(clientIp));

        MvcResult loginResult = mockMvc.perform(post("/api/v1/auth/login")
                        .with(req -> {
                            req.setRemoteAddr(clientIp);
                            return req;
                        })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("identifier", regNumber, "pin", "49281"))))
                .andExpect(status().isOk())
                .andReturn();
        Cookie refreshCookie = loginResult.getResponse().getCookie("refreshToken");
        assertThat(refreshCookie).isNotNull();

        Student candidate = studentRepository.findByRegistrationNumberIgnoreCase(regNumber).orElseThrow();
        UUID examSessionId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO exam_sessions (id, user_id, status, created_at) VALUES (?, ?, 'FLAGGED_TAB_SWITCH', NOW())",
                examSessionId,
                candidate.getId());

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .with(req -> {
                            req.setRemoteAddr(clientIp);
                            return req;
                        })
                        .cookie(refreshCookie))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("EXAM_MALPRACTICE_BLOCKED"));

        // Deleted student must also be denied on refresh even before async User sync completes
        jdbcTemplate.update("DELETE FROM exam_sessions WHERE id = ?", examSessionId);
        studentRepository.delete(candidate);
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .with(req -> {
                            req.setRemoteAddr(clientIp);
                            return req;
                        })
                        .cookie(refreshCookie))
                .andExpect(status().isUnauthorized());
    }

    private String loginAs(String ip, String identifier, String pin) throws Exception {
        touchedIpKeys.add(SessionRedisKeys.loginIpKey(ip));
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .with(req -> {
                            req.setRemoteAddr(ip);
                            return req;
                        })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("identifier", identifier, "pin", pin))))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        return json.path("data").path("accessToken").asText();
    }

    private String createCandidate(String rawPin) {
        String regNumber = "JAMB-2026-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        String hash = passwordEncoder.encode(rawPin);

        Student student = new Student();
        student.setName("Lifecycle Candidate");
        student.setRegistrationNumber(regNumber);
        student.setPinHash(hash);
        student.setPinResetRequired(false);
        Student saved = studentRepository.save(student);

        User user = new User();
        user.setId(saved.getId());
        user.setIdentifier(regNumber);
        user.setPinHash(hash);
        user.setRole("ROLE_STUDENT");
        user.setPinResetRequired(false);
        userRepository.save(user);

        createdStudentIds.add(saved.getId());
        createdIdentifiers.add(regNumber);
        return regNumber;
    }

    private String createStaff(String role, String rawPin) {
        String identifier = "STAFF-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setIdentifier(identifier);
        user.setPinHash(passwordEncoder.encode(rawPin));
        user.setRole(role);
        user.setPinResetRequired(false);
        userRepository.save(user);
        createdIdentifiers.add(identifier);
        return identifier;
    }
}
