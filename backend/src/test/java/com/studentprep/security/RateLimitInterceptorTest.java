package com.studentprep.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.studentprep.common.SessionRedisKeys;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RateLimitInterceptorTest {

    private static final String TEST_SECRET = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private HttpServletRequest request;

    @Mock
    private HttpServletResponse response;

    private ObjectMapper objectMapper;
    private JwtUtil jwtUtil;
    private RateLimitInterceptor interceptor;
    private StringWriter responseWriter;

    @BeforeEach
    void setUp() throws IOException {
        SecurityContextHolder.clearContext();
        objectMapper = new ObjectMapper();
        jwtUtil = new JwtUtil(TEST_SECRET, 900_000L, 604_800_000L);
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        responseWriter = new StringWriter();
        lenient().when(response.getWriter()).thenReturn(new PrintWriter(responseWriter));
        lenient().when(request.getMethod()).thenReturn("POST");

        interceptor = new RateLimitInterceptor(
                redisTemplate,
                jwtUtil,
                objectMapper,
                5,
                15,
                60,
                30,
                15,
                10);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void loginIpLimit_AllowsWithinLimitAndReturns429ProblemDetailWithRetryAfterWhenExceeded() throws Exception {
        when(request.getRequestURI()).thenReturn("/api/v1/auth/login");
        when(request.getRemoteAddr()).thenReturn("10.10.0.5");

        String ipKey = SessionRedisKeys.loginIpKey("10.10.0.5");
        when(valueOperations.increment(ipKey)).thenReturn(1L, 61L);
        when(redisTemplate.getExpire(ipKey, TimeUnit.SECONDS)).thenReturn(-1L, 42L);

        assertTrue(interceptor.preHandle(request, response, new Object()));
        verify(redisTemplate).expire(eq(ipKey), eq(Duration.ofMinutes(1)));

        assertFalse(interceptor.preHandle(request, response, new Object()));
        verify(response).setStatus(429);
        verify(response).setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        verify(response).setHeader(HttpHeaders.RETRY_AFTER, "42");

        JsonNode json = objectMapper.readTree(responseWriter.toString());
        assertEquals(429, json.get("status").asInt());
        assertEquals("RATE_LIMIT_EXCEEDED", json.get("code").asText());
        assertEquals("/api/v1/auth/login", json.get("instance").asText());
        assertEquals(42L, json.get("retryAfterSeconds").asLong());
    }

    @Test
    void refresh_EnforcesPerIpAndPerSubjectCookieLimits() throws Exception {
        String refreshToken = jwtUtil.generateRefreshToken("student-009", "ROLE_STUDENT", "jti-r-1");
        when(request.getRequestURI()).thenReturn("/api/v1/auth/refresh");
        when(request.getRemoteAddr()).thenReturn("192.168.1.25");
        when(request.getCookies()).thenReturn(new Cookie[] { new Cookie("refreshToken", refreshToken) });

        String ipKey = SessionRedisKeys.refreshIpKey("192.168.1.25");
        String subjectKey = SessionRedisKeys.refreshIdKey("student-009");
        when(valueOperations.increment(ipKey)).thenReturn(1L);
        when(redisTemplate.getExpire(ipKey, TimeUnit.SECONDS)).thenReturn(55L);
        when(valueOperations.increment(subjectKey)).thenReturn(16L);
        when(redisTemplate.getExpire(subjectKey, TimeUnit.SECONDS)).thenReturn(38L);

        assertFalse(interceptor.preHandle(request, response, new Object()));
        verify(response).setStatus(429);
        verify(response).setHeader(HttpHeaders.RETRY_AFTER, "38");
    }

    @Test
    void sync_RequiresAuthAndFailsOpenDuringRedisOutage() throws Exception {
        when(request.getRequestURI()).thenReturn("/api/v1/exams/active/sync");

        assertFalse(interceptor.preHandle(request, response, new Object()));
        verify(response).setStatus(401);

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        "JAMB-2026-001",
                        null,
                        List.of(new SimpleGrantedAuthority("ROLE_STUDENT"))));
        String syncKey = SessionRedisKeys.syncKey("JAMB-2026-001");
        when(valueOperations.increment(syncKey)).thenThrow(new QueryTimeoutException("Redis down"));

        assertTrue(interceptor.preHandle(request, response, new Object()));
    }

    @Test
    void authLogin_UsesBoundedLocalFallbackWhenRedisIsUnavailable() throws Exception {
        RateLimitInterceptor tightInterceptor = new RateLimitInterceptor(
                redisTemplate, jwtUtil, objectMapper, 5, 15, 2, 2, 2, 10);
        when(request.getRequestURI()).thenReturn("/api/v1/auth/login");
        when(request.getRemoteAddr()).thenReturn("10.0.0.99");
        when(valueOperations.increment(any())).thenThrow(new QueryTimeoutException("Redis down"));

        assertTrue(tightInterceptor.preHandle(request, response, new Object()));
        assertTrue(tightInterceptor.preHandle(request, response, new Object()));
        assertFalse(tightInterceptor.preHandle(request, response, new Object()));

        verify(response).setStatus(429);
        verify(response).setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        JsonNode json = objectMapper.readTree(responseWriter.toString());
        assertEquals("RATE_LIMIT_EXCEEDED", json.get("code").asText());
    }

    @Test
    void loginIdentifierLockout_RecordsClearsAndFallsBackLocally() {
        String key = SessionRedisKeys.loginFailIdKey("JAMB-007");
        when(valueOperations.get(key)).thenReturn("5");
        when(redisTemplate.getExpire(key, TimeUnit.SECONDS)).thenReturn(700L);

        RateLimitInterceptor.LockoutStatus locked = interceptor.checkLoginIdentifierLockout(" jamb-007 ");
        assertTrue(locked.locked());
        assertEquals(700L, locked.retryAfterSeconds());

        interceptor.clearLoginFailures("jamb-007");
        verify(redisTemplate).delete(key);

        RateLimitInterceptor outageInterceptor = new RateLimitInterceptor(
                redisTemplate, jwtUtil, objectMapper, 2, 15, 60, 30, 15, 10);
        when(valueOperations.increment(key)).thenThrow(new QueryTimeoutException("Redis down"));
        when(valueOperations.get(key)).thenThrow(new QueryTimeoutException("Redis down"));

        outageInterceptor.recordLoginFailure("jamb-007");
        assertFalse(outageInterceptor.checkLoginIdentifierLockout("JAMB-007").locked());

        outageInterceptor.recordLoginFailure("JAMB-007");
        assertTrue(outageInterceptor.checkLoginIdentifierLockout("jamb-007").locked());

        outageInterceptor.clearLoginFailures("jamb-007");
        assertFalse(outageInterceptor.checkLoginIdentifierLockout("jamb-007").locked());
    }
}
