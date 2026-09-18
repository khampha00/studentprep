package com.studentprep.security;

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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class JwtAuthenticationFilterTest {

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

    private JwtAuthenticationFilter filter;
    private StringWriter responseWriter;

    @BeforeEach
    void setUp() throws IOException {
        SecurityContextHolder.clearContext();
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        filter = new JwtAuthenticationFilter(jwtUtil, redisTemplate);
        responseWriter = new StringWriter();
        lenient().when(response.getWriter()).thenReturn(new PrintWriter(responseWriter));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void doFilter_WhenMismatchedJtiDueToConcurrentLogin_Returns401AndStopsChain() throws ServletException, IOException {
        String token = "sample.jwt.token";
        when(request.getHeader("Authorization")).thenReturn("Bearer " + token);
        when(request.getRequestURI()).thenReturn("/api/v1/exams/active/session");
        when(jwtUtil.isTokenValid(token)).thenReturn(true);
        when(jwtUtil.extractIdentifier(token)).thenReturn("STUDENT-001");
        when(jwtUtil.extractJti(token)).thenReturn("old-jti-device-1");

        // Redis has the new JTI set when device 2 logged in
        when(valueOperations.get("session:STUDENT-001")).thenReturn("new-jti-device-2");

        filter.doFilterInternal(request, response, filterChain);

        verify(response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        verify(response).setContentType("application/json");
        verify(filterChain, never()).doFilter(request, response);
        assertNull(SecurityContextHolder.getContext().getAuthentication());
        assertTrue(responseWriter.toString().contains("Session active on another device"));
    }

    @Test
    void doFilter_WhenJtiMatches_SetsSecurityContextAndContinuesChain() throws ServletException, IOException {
        String token = "sample.jwt.token";
        when(request.getHeader("Authorization")).thenReturn("Bearer " + token);
        when(jwtUtil.isTokenValid(token)).thenReturn(true);
        when(jwtUtil.extractIdentifier(token)).thenReturn("STUDENT-001");
        when(jwtUtil.extractJti(token)).thenReturn("matching-jti");
        when(jwtUtil.extractRole(token)).thenReturn("ROLE_STUDENT");

        when(valueOperations.get("session:STUDENT-001")).thenReturn("matching-jti");

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        assertNotNull(SecurityContextHolder.getContext().getAuthentication());
        assertEquals("STUDENT-001", SecurityContextHolder.getContext().getAuthentication().getName());
    }

    @Test
    void doFilter_WhenNoAuthHeader_PassesThroughToChain() throws ServletException, IOException {
        when(request.getHeader("Authorization")).thenReturn(null);

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }
}
