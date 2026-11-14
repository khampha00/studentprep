package com.studentprep.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.studentprep.common.SessionRedisKeys;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
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
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JwtAuthenticationFilterTest {

    private static final String TEST_SECRET = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @Mock
    private JwtUtil jwtUtil;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private HttpServletRequest request;

    @Mock
    private HttpServletResponse response;

    @Mock
    private FilterChain filterChain;

    private ObjectMapper objectMapper;
    private JwtAuthenticationFilter filter;
    private StringWriter responseWriter;

    @BeforeEach
    void setUp() throws IOException {
        SecurityContextHolder.clearContext();
        objectMapper = new ObjectMapper();
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        filter = new JwtAuthenticationFilter(jwtUtil, redisTemplate, objectMapper);
        responseWriter = new StringWriter();
        lenient().when(response.getWriter()).thenReturn(new PrintWriter(responseWriter));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void doFilter_WhenMismatchedJtiDueToConcurrentLogin_Returns401ProblemDetailAndStopsChain()
            throws ServletException, IOException {
        String token = "sample.jwt.token";
        when(request.getHeader("Authorization")).thenReturn("Bearer " + token);
        when(request.getRequestURI()).thenReturn("/api/v1/exams/active/session");
        when(jwtUtil.isAccessTokenValid(token)).thenReturn(true);
        when(jwtUtil.extractIdentifier(token)).thenReturn("STUDENT-001");
        when(jwtUtil.extractJti(token)).thenReturn("old-jti-device-1");
        when(jwtUtil.extractRole(token)).thenReturn("ROLE_STUDENT");

        when(valueOperations.get(SessionRedisKeys.sessionKey("STUDENT-001"))).thenReturn("new-jti-device-2");

        filter.doFilterInternal(request, response, filterChain);

        verify(response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        verify(response).setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        verify(filterChain, never()).doFilter(request, response);
        assertNull(SecurityContextHolder.getContext().getAuthentication());

        JsonNode json = objectMapper.readTree(responseWriter.toString());
        assertEquals(401, json.get("status").asInt());
        assertEquals("SESSION_EVICTED", json.get("code").asText());
        assertEquals("Session active on another device", json.get("detail").asText());
        assertEquals("/api/v1/exams/active/session", json.get("instance").asText());
    }

    @Test
    void doFilter_WhenJtiMatchesMixedCaseIdentifier_CanonicalizesAndSetsSecurityContext()
            throws ServletException, IOException {
        String token = "sample.jwt.token";
        when(request.getHeader("Authorization")).thenReturn("Bearer " + token);
        when(jwtUtil.isAccessTokenValid(token)).thenReturn(true);
        when(jwtUtil.extractIdentifier(token)).thenReturn("  student-001 ");
        when(jwtUtil.extractJti(token)).thenReturn("matching-jti");
        when(jwtUtil.extractRole(token)).thenReturn("ROLE_STUDENT");

        when(valueOperations.get("session:STUDENT-001")).thenReturn("matching-jti");

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        assertNotNull(SecurityContextHolder.getContext().getAuthentication());
        assertEquals("STUDENT-001", SecurityContextHolder.getContext().getAuthentication().getName());
    }

    @Test
    void doFilter_WhenRefreshTokenSentAsBearerToken_DoesNotAuthenticate()
            throws ServletException, IOException {
        JwtUtil realJwtUtil = new JwtUtil(TEST_SECRET, 900_000L, 604_800_000L);
        JwtAuthenticationFilter realFilter = new JwtAuthenticationFilter(realJwtUtil, redisTemplate, objectMapper);
        String refreshToken = realJwtUtil.generateRefreshToken("STUDENT-001", "ROLE_STUDENT", "jti-refresh");

        when(request.getHeader("Authorization")).thenReturn("Bearer " + refreshToken);

        realFilter.doFilterInternal(request, response, filterChain);

        verify(valueOperations, never()).get(anyString());
        verify(filterChain).doFilter(request, response);
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void doFilter_WhenRedisUnavailableOnProtectedEndpoint_Returns503ProblemDetailAndFailsClosed()
            throws ServletException, IOException {
        String token = "sample.jwt.token";
        when(request.getHeader("Authorization")).thenReturn("Bearer " + token);
        when(request.getRequestURI()).thenReturn("/api/v1/exams/active/session");
        when(jwtUtil.isAccessTokenValid(token)).thenReturn(true);
        when(jwtUtil.extractIdentifier(token)).thenReturn("STUDENT-001");
        when(jwtUtil.extractJti(token)).thenReturn("jti-1");
        when(jwtUtil.extractRole(token)).thenReturn("ROLE_STUDENT");
        when(valueOperations.get("session:STUDENT-001")).thenThrow(new QueryTimeoutException("Redis down"));

        filter.doFilterInternal(request, response, filterChain);

        verify(response).setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        verify(response).setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        verify(filterChain, never()).doFilter(request, response);
        assertNull(SecurityContextHolder.getContext().getAuthentication());

        JsonNode json = objectMapper.readTree(responseWriter.toString());
        assertEquals(503, json.get("status").asInt());
        assertEquals("SESSION_STORE_UNAVAILABLE", json.get("code").asText());
    }

    @Test
    void doFilter_WhenNoAuthHeader_PassesThroughToChain() throws ServletException, IOException {
        when(request.getHeader("Authorization")).thenReturn(null);

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }
}
