package com.studentprep.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.studentprep.common.SessionRedisKeys;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);

    private final JwtUtil jwtUtil;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public JwtAuthenticationFilter(
            JwtUtil jwtUtil,
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper) {
        this.jwtUtil = jwtUtil;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        final String authHeader = request.getHeader("Authorization");

        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }

        final String token = authHeader.substring(7).trim();
        if (token.isEmpty()) {
            filterChain.doFilter(request, response);
            return;
        }

        if (SecurityContextHolder.getContext().getAuthentication() == null && jwtUtil.isAccessTokenValid(token)) {
            String rawIdentifier = jwtUtil.extractIdentifier(token);
            String jti = jwtUtil.extractJti(token);
            String role = jwtUtil.extractRole(token);

            if (isBlank(rawIdentifier) || isBlank(jti) || isBlank(role)) {
                filterChain.doFilter(request, response);
                return;
            }

            String canonicalIdentifier = SessionRedisKeys.canonicalIdentifier(rawIdentifier);
            String requestUri = request.getRequestURI();
            boolean isAuthEndpoint = requestUri != null && requestUri.startsWith("/api/v1/auth/");

            String storedJti;
            try {
                storedJti = redisTemplate.opsForValue().get(SessionRedisKeys.sessionKey(canonicalIdentifier));
            } catch (DataAccessException ex) {
                log.warn("Redis unavailable during session lookup for {}", canonicalIdentifier, ex);
                if (!isAuthEndpoint) {
                    writeProblemResponse(
                            response,
                            HttpStatus.SERVICE_UNAVAILABLE,
                            "Session store temporarily unavailable",
                            "SESSION_STORE_UNAVAILABLE",
                            requestUri);
                    return;
                }
                filterChain.doFilter(request, response);
                return;
            }

            if (storedJti == null || !storedJti.equals(jti)) {
                if (!isAuthEndpoint) {
                    writeProblemResponse(
                            response,
                            HttpStatus.UNAUTHORIZED,
                            "Session active on another device",
                            "SESSION_EVICTED",
                            requestUri);
                    return;
                }
                filterChain.doFilter(request, response);
                return;
            }

            // Reconstruct authorities strictly from verified JWT claims to remain stateless.
            UsernamePasswordAuthenticationToken authToken = new UsernamePasswordAuthenticationToken(
                    canonicalIdentifier,
                    null,
                    Collections.singletonList(new SimpleGrantedAuthority(role)));
            authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
            SecurityContextHolder.getContext().setAuthentication(authToken);
        }

        filterChain.doFilter(request, response);
    }

    private void writeProblemResponse(
            HttpServletResponse response,
            HttpStatus status,
            String detail,
            String code,
            String instanceUri) throws IOException {
        Map<String, Object> problem = new LinkedHashMap<>();
        problem.put("type", "about:blank");
        problem.put("title", status.getReasonPhrase());
        problem.put("status", status.value());
        problem.put("detail", detail);
        if (!isBlank(instanceUri)) {
            problem.put("instance", instanceUri);
        }
        problem.put("code", code);

        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(objectMapper.writeValueAsString(problem));
        response.getWriter().flush();
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
