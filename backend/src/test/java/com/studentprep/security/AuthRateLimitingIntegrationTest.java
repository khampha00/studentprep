package com.studentprep.security;

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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class AuthRateLimitingIntegrationTest {

    private final MockMvc mockMvc;
    private final ObjectMapper objectMapper;
    private final PasswordEncoder passwordEncoder;
    private final StudentRepository studentRepository;
    private final UserRepository userRepository;
    private final StringRedisTemplate redisTemplate;

    private final List<UUID> createdStudentIds = new ArrayList<>();
    private final List<String> createdIdentifiers = new ArrayList<>();
    private final List<String> touchedIpKeys = new ArrayList<>();

    AuthRateLimitingIntegrationTest(
            MockMvc mockMvc,
            ObjectMapper objectMapper,
            PasswordEncoder passwordEncoder,
            StudentRepository studentRepository,
            UserRepository userRepository,
            StringRedisTemplate redisTemplate) {
        this.mockMvc = mockMvc;
        this.objectMapper = objectMapper;
        this.passwordEncoder = passwordEncoder;
        this.studentRepository = studentRepository;
        this.userRepository = userRepository;
        this.redisTemplate = redisTemplate;
    }

    @AfterEach
    void cleanup() {
        for (UUID studentId : createdStudentIds) {
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
        for (String key : touchedIpKeys) {
            redisTemplate.delete(key);
        }
        createdStudentIds.clear();
        createdIdentifiers.clear();
        touchedIpKeys.clear();
    }

    @Test
    void loginFailureLockout_ClearsOnSuccessAndLocksOutSixthAttemptEvenWithValidPin() throws Exception {
        String regNumber = createCandidate("13579");
        String ip = "10.99.2.10";
        touchedIpKeys.add(SessionRedisKeys.loginIpKey(ip));

        for (int attempt = 0; attempt < 4; attempt++) {
            mockMvc.perform(post("/api/v1/auth/login")
                            .with(req -> {
                                req.setRemoteAddr(ip);
                                return req;
                            })
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("identifier", regNumber, "pin", "00000"))))
                    .andExpect(status().isUnauthorized());
        }

        // Valid login clears failure counter before lockout threshold
        MvcResult successResult = mockMvc.perform(post("/api/v1/auth/login")
                        .with(req -> {
                            req.setRemoteAddr(ip);
                            return req;
                        })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("identifier", regNumber, "pin", "13579"))))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(redisTemplate.opsForValue().get(SessionRedisKeys.loginFailIdKey(regNumber))).isNull();

        String accessToken = objectMapper.readTree(successResult.getResponse().getContentAsString())
                .path("data").path("accessToken").asText();
        mockMvc.perform(post("/api/v1/auth/logout")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isOk());

        // Five consecutive failures trigger 15-minute lockout on 6th attempt even with valid PIN
        for (int attempt = 0; attempt < 5; attempt++) {
            mockMvc.perform(post("/api/v1/auth/login")
                            .with(req -> {
                                req.setRemoteAddr(ip);
                                return req;
                            })
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("identifier", regNumber, "pin", "00000"))))
                    .andExpect(status().isUnauthorized());
        }

        mockMvc.perform(post("/api/v1/auth/login")
                        .with(req -> {
                            req.setRemoteAddr(ip);
                            return req;
                        })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("identifier", regNumber, "pin", "13579"))))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists(HttpHeaders.RETRY_AFTER))
                .andExpect(jsonPath("$.code").value("RATE_LIMIT_EXCEEDED"));
    }

    @Test
    void loginPerIpBurstLimit_Returns429On61stRequest() throws Exception {
        String ip = "10.99.2.20";
        touchedIpKeys.add(SessionRedisKeys.loginIpKey(ip));

        for (int i = 0; i < 60; i++) {
            String probeId = "PROBE-MISSING-" + i;
            createdIdentifiers.add(probeId);
            mockMvc.perform(post("/api/v1/auth/login")
                            .with(req -> {
                                req.setRemoteAddr(ip);
                                return req;
                            })
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("identifier", probeId, "pin", "12345"))))
                    .andExpect(status().isUnauthorized());
        }

        mockMvc.perform(post("/api/v1/auth/login")
                        .with(req -> {
                            req.setRemoteAddr(ip);
                            return req;
                        })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("identifier", "PROBE-FINAL", "pin", "12345"))))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists(HttpHeaders.RETRY_AFTER))
                .andExpect(jsonPath("$.code").value("RATE_LIMIT_EXCEEDED"));
    }

    @Test
    void refreshAndSyncLimits_Enforce15PerSubject30PerIpAnd10PerStudentSync() throws Exception {
        String regNumber = createCandidate("24680");
        String loginIp = "10.99.3.0";
        touchedIpKeys.add(SessionRedisKeys.loginIpKey(loginIp));

        MvcResult loginResult = mockMvc.perform(post("/api/v1/auth/login")
                        .with(req -> {
                            req.setRemoteAddr(loginIp);
                            return req;
                        })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("identifier", regNumber, "pin", "24680"))))
                .andExpect(status().isOk())
                .andReturn();
        String accessToken = objectMapper.readTree(loginResult.getResponse().getContentAsString())
                .path("data").path("accessToken").asText();
        Cookie refreshCookie = loginResult.getResponse().getCookie("refreshToken");
        assertThat(refreshCookie).isNotNull();

        // 15 refresh calls across rotated IPs succeed; 16th hits per-subject 15/min limit
        for (int i = 1; i <= 15; i++) {
            String rotatedIp = "10.99.3." + i;
            touchedIpKeys.add(SessionRedisKeys.refreshIpKey(rotatedIp));
            mockMvc.perform(post("/api/v1/auth/refresh")
                            .with(req -> {
                                req.setRemoteAddr(rotatedIp);
                                return req;
                            })
                            .cookie(refreshCookie))
                    .andExpect(status().isOk());
        }

        String subjectLimitIp = "10.99.3.99";
        touchedIpKeys.add(SessionRedisKeys.refreshIpKey(subjectLimitIp));
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .with(req -> {
                            req.setRemoteAddr(subjectLimitIp);
                            return req;
                        })
                        .cookie(refreshCookie))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists(HttpHeaders.RETRY_AFTER))
                .andExpect(jsonPath("$.code").value("RATE_LIMIT_EXCEEDED"));

        // Per-IP refresh limit: 30 calls from one IP; 31st returns 429
        String spamIp = "10.99.4.10";
        touchedIpKeys.add(SessionRedisKeys.refreshIpKey(spamIp));
        for (int i = 0; i < 30; i++) {
            mockMvc.perform(post("/api/v1/auth/refresh")
                            .with(req -> {
                                req.setRemoteAddr(spamIp);
                                return req;
                            }))
                    .andExpect(status().isUnauthorized());
        }
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .with(req -> {
                            req.setRemoteAddr(spamIp);
                            return req;
                        }))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists(HttpHeaders.RETRY_AFTER))
                .andExpect(jsonPath("$.code").value("RATE_LIMIT_EXCEEDED"));

        // Per-student exam active sync limit: 10 calls allowed, 11th returns 429
        for (int i = 0; i < 10; i++) {
            mockMvc.perform(post("/api/v1/exams/active/sync")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().is(not(429)));
        }
        mockMvc.perform(post("/api/v1/exams/active/sync")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists(HttpHeaders.RETRY_AFTER))
                .andExpect(jsonPath("$.code").value("RATE_LIMIT_EXCEEDED"));
    }

    private String createCandidate(String rawPin) {
        String regNumber = "JAMB-2026-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        String hash = passwordEncoder.encode(rawPin);

        Student student = new Student();
        student.setName("Rate Limit Candidate");
        student.setExamCenter("Center R");
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
}
