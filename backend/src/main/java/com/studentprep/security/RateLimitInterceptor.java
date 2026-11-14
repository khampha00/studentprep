package com.studentprep.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.studentprep.common.SessionRedisKeys;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    public static final String REFRESH_COOKIE_NAME = "refreshToken";
    private static final int MAX_LOCAL_BUCKETS = 10_000;

    private static final Logger log = LoggerFactory.getLogger(RateLimitInterceptor.class);

    private final StringRedisTemplate redisTemplate;
    private final JwtUtil jwtUtil;
    private final ObjectMapper objectMapper;
    private final int loginMaxFailuresPerIdentifier;
    private final int loginLockoutMinutes;
    private final int loginMaxPerIpPerMinute;
    private final int refreshMaxPerIpPerMinute;
    private final int refreshMaxPerSubjectPerMinute;
    private final int syncMaxPerStudentPerMinute;

    private final ConcurrentHashMap<String, LocalWindowCounter> localFallbackBuckets = new ConcurrentHashMap<>();

    public RateLimitInterceptor(
            StringRedisTemplate redisTemplate,
            JwtUtil jwtUtil,
            ObjectMapper objectMapper,
            @Value("${security.rate-limit.login-max-failures-per-identifier:5}") int loginMaxFailuresPerIdentifier,
            @Value("${security.rate-limit.login-lockout-minutes:15}") int loginLockoutMinutes,
            @Value("${security.rate-limit.login-max-per-ip-per-minute:60}") int loginMaxPerIpPerMinute,
            @Value("${security.rate-limit.refresh-max-per-ip-per-minute:30}") int refreshMaxPerIpPerMinute,
            @Value("${security.rate-limit.refresh-max-per-subject-per-minute:15}") int refreshMaxPerSubjectPerMinute,
            @Value("${security.rate-limit.sync-max-per-student-per-minute:10}") int syncMaxPerStudentPerMinute) {
        this.redisTemplate = redisTemplate;
        this.jwtUtil = jwtUtil;
        this.objectMapper = objectMapper;
        this.loginMaxFailuresPerIdentifier = Math.max(1, loginMaxFailuresPerIdentifier);
        this.loginLockoutMinutes = Math.max(1, loginLockoutMinutes);
        this.loginMaxPerIpPerMinute = Math.max(1, loginMaxPerIpPerMinute);
        this.refreshMaxPerIpPerMinute = Math.max(1, refreshMaxPerIpPerMinute);
        this.refreshMaxPerSubjectPerMinute = Math.max(1, refreshMaxPerSubjectPerMinute);
        this.syncMaxPerStudentPerMinute = Math.max(1, syncMaxPerStudentPerMinute);
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        String uri = request.getRequestURI();
        if (uri == null) {
            return true;
        }

        if ("/api/v1/auth/login".equals(uri)) {
            String ipKey = SessionRedisKeys.loginIpKey(resolveClientIp(request));
            RateLimitDecision decision = consumeFixedWindow(ipKey, loginMaxPerIpPerMinute, Duration.ofMinutes(1), false);
            if (!decision.allowed()) {
                writeProblemResponse(
                        response,
                        HttpStatus.TOO_MANY_REQUESTS,
                        "Too many login requests from this IP. Please try again later.",
                        "RATE_LIMIT_EXCEEDED",
                        uri,
                        decision.retryAfterSeconds());
                return false;
            }
            return true;
        }

        if ("/api/v1/auth/refresh".equals(uri)) {
            String ipKey = SessionRedisKeys.refreshIpKey(resolveClientIp(request));
            RateLimitDecision ipDecision = consumeFixedWindow(
                    ipKey, refreshMaxPerIpPerMinute, Duration.ofMinutes(1), false);
            if (!ipDecision.allowed()) {
                writeProblemResponse(
                        response,
                        HttpStatus.TOO_MANY_REQUESTS,
                        "Too many token refresh requests from this IP. Please try again later.",
                        "RATE_LIMIT_EXCEEDED",
                        uri,
                        ipDecision.retryAfterSeconds());
                return false;
            }

            String refreshToken = extractRefreshCookie(request);
            if (refreshToken != null && jwtUtil.isRefreshTokenValid(refreshToken)) {
                String identifier = jwtUtil.extractIdentifier(refreshToken);
                if (identifier != null && !identifier.trim().isEmpty()) {
                    String subjectKey = SessionRedisKeys.refreshIdKey(identifier);
                    RateLimitDecision subjectDecision = consumeFixedWindow(
                            subjectKey, refreshMaxPerSubjectPerMinute, Duration.ofMinutes(1), false);
                    if (!subjectDecision.allowed()) {
                        writeProblemResponse(
                                response,
                                HttpStatus.TOO_MANY_REQUESTS,
                                "Too many token refresh requests for this account. Please try again later.",
                                "RATE_LIMIT_EXCEEDED",
                                uri,
                                subjectDecision.retryAfterSeconds());
                        return false;
                    }
                }
            }
            return true;
        }

        if ("/api/v1/exams/active/sync".equals(uri)) {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth == null || auth.getName() == null || auth.getName().trim().isEmpty()) {
                writeProblemResponse(
                        response,
                        HttpStatus.UNAUTHORIZED,
                        "Authentication is required to sync active exam progress.",
                        "UNAUTHORIZED",
                        uri,
                        0L);
                return false;
            }

            String syncKey = SessionRedisKeys.syncKey(auth.getName());
            // Exam active sync fails open during Redis outage so legitimate answers are never blocked.
            RateLimitDecision decision = consumeFixedWindow(
                    syncKey, syncMaxPerStudentPerMinute, Duration.ofMinutes(1), true);
            if (!decision.allowed()) {
                writeProblemResponse(
                        response,
                        HttpStatus.TOO_MANY_REQUESTS,
                        "Exam sync rate limit exceeded. Please retry shortly.",
                        "RATE_LIMIT_EXCEEDED",
                        uri,
                        decision.retryAfterSeconds());
                return false;
            }
            return true;
        }

        return true;
    }

    /**
     * Checks whether the identifier is currently locked out from failed login attempts
     * without incrementing the failure counter.
     */
    public LockoutStatus checkLoginIdentifierLockout(String identifier) {
        if (identifier == null || identifier.trim().isEmpty()) {
            return new LockoutStatus(false, 0L);
        }
        String key = SessionRedisKeys.loginFailIdKey(identifier);
        long defaultLockoutSeconds = Duration.ofMinutes(loginLockoutMinutes).toSeconds();

        try {
            String rawCount = redisTemplate.opsForValue().get(key);
            if (rawCount != null) {
                long count = Long.parseLong(rawCount);
                if (count >= loginMaxFailuresPerIdentifier) {
                    Long ttl = redisTemplate.getExpire(key, TimeUnit.SECONDS);
                    long retryAfter = (ttl != null && ttl > 0L) ? ttl : defaultLockoutSeconds;
                    return new LockoutStatus(true, retryAfter);
                }
            }
            return new LockoutStatus(false, 0L);
        } catch (DataAccessException | NumberFormatException ex) {
            log.warn("Redis unavailable checking login lockout for key {}; using bounded local fallback", key);
            return checkLocalLockout(key);
        }
    }

    /**
     * Records a failed login attempt for the canonical identifier within the lockout window.
     */
    public void recordLoginFailure(String identifier) {
        if (identifier == null || identifier.trim().isEmpty()) {
            return;
        }
        String key = SessionRedisKeys.loginFailIdKey(identifier);
        Duration window = Duration.ofMinutes(loginLockoutMinutes);

        try {
            Long count = redisTemplate.opsForValue().increment(key);
            Long ttlSeconds = redisTemplate.getExpire(key, TimeUnit.SECONDS);
            if ((count != null && count == 1L) || ttlSeconds == null || ttlSeconds < 0L) {
                redisTemplate.expire(key, window);
            }
        } catch (DataAccessException ex) {
            log.warn("Redis unavailable recording login failure for key {}; using bounded local fallback", key);
            incrementLocalBucket(key, window);
        }
    }

    /**
     * Clears any login failure counter after successful authentication.
     */
    public void clearLoginFailures(String identifier) {
        if (identifier == null || identifier.trim().isEmpty()) {
            return;
        }
        String key = SessionRedisKeys.loginFailIdKey(identifier);
        localFallbackBuckets.remove(key);
        try {
            redisTemplate.delete(key);
        } catch (DataAccessException ex) {
            log.warn("Redis unavailable clearing login failure key {}", key);
        }
    }

    public Map<String, Object> buildRateLimitProblem(
            String detail,
            String instanceUri,
            long retryAfterSeconds) {
        return createProblemBody(
                HttpStatus.TOO_MANY_REQUESTS,
                detail,
                "RATE_LIMIT_EXCEEDED",
                instanceUri,
                retryAfterSeconds);
    }

    private RateLimitDecision consumeFixedWindow(String key, int maxRequests, Duration window, boolean failOpenOnRedisError) {
        try {
            Long count = redisTemplate.opsForValue().increment(key);
            if (count == null) {
                return failOpenOnRedisError
                        ? new RateLimitDecision(true, 0L)
                        : consumeLocalBucket(key, maxRequests, window);
            }
            Long ttlSeconds = redisTemplate.getExpire(key, TimeUnit.SECONDS);
            if (count == 1L || ttlSeconds == null || ttlSeconds < 0L) {
                redisTemplate.expire(key, window);
                ttlSeconds = window.toSeconds();
            }
            if (count > maxRequests) {
                return new RateLimitDecision(false, Math.max(1L, ttlSeconds));
            }
            return new RateLimitDecision(true, 0L);
        } catch (DataAccessException ex) {
            if (failOpenOnRedisError) {
                log.warn("Redis unavailable during fail-open rate limit check for key {}", key);
                return new RateLimitDecision(true, 0L);
            }
            log.warn("Redis unavailable during auth rate limit check for key {}; applying bounded local fallback", key);
            return consumeLocalBucket(key, maxRequests, window);
        }
    }

    private RateLimitDecision consumeLocalBucket(String key, int maxRequests, Duration window) {
        long now = System.currentTimeMillis();
        LocalWindowCounter bucket = getOrCreateLocalBucket(key, now, window);
        if (bucket == null) {
            // Bounded capacity exhausted during outage: shed auth traffic rather than allow unbounded memory or brute-force.
            return new RateLimitDecision(false, Math.max(1L, window.toSeconds()));
        }
        long count = bucket.incrementAndGetIfValid(now, window.toMillis());
        if (count > maxRequests) {
            return new RateLimitDecision(false, bucket.remainingSeconds(now));
        }
        return new RateLimitDecision(true, 0L);
    }

    private LockoutStatus checkLocalLockout(String key) {
        long now = System.currentTimeMillis();
        LocalWindowCounter bucket = localFallbackBuckets.get(key);
        if (bucket == null) {
            return new LockoutStatus(false, 0L);
        }
        if (bucket.isExpired(now)) {
            localFallbackBuckets.remove(key, bucket);
            return new LockoutStatus(false, 0L);
        }
        if (bucket.currentCount(now) >= loginMaxFailuresPerIdentifier) {
            return new LockoutStatus(true, bucket.remainingSeconds(now));
        }
        return new LockoutStatus(false, 0L);
    }

    private void incrementLocalBucket(String key, Duration window) {
        long now = System.currentTimeMillis();
        LocalWindowCounter bucket = getOrCreateLocalBucket(key, now, window);
        if (bucket != null) {
            bucket.incrementAndGetIfValid(now, window.toMillis());
        }
    }

    private LocalWindowCounter getOrCreateLocalBucket(String key, long now, Duration window) {
        LocalWindowCounter existing = localFallbackBuckets.get(key);
        if (existing != null && !existing.isExpired(now)) {
            return existing;
        }
        if (localFallbackBuckets.size() >= MAX_LOCAL_BUCKETS) {
            localFallbackBuckets.entrySet().removeIf(entry -> entry.getValue().isExpired(now));
            if (localFallbackBuckets.size() >= MAX_LOCAL_BUCKETS) {
                return null;
            }
        }
        LocalWindowCounter created = new LocalWindowCounter(now + window.toMillis());
        LocalWindowCounter raced = localFallbackBuckets.putIfAbsent(key, created);
        return raced != null ? raced : created;
    }

    private void writeProblemResponse(
            HttpServletResponse response,
            HttpStatus status,
            String detail,
            String code,
            String instanceUri,
            long retryAfterSeconds) throws IOException {
        Map<String, Object> problem = createProblemBody(status, detail, code, instanceUri, retryAfterSeconds);
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        if (status == HttpStatus.TOO_MANY_REQUESTS) {
            response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(Math.max(1L, retryAfterSeconds)));
        }
        response.getWriter().write(objectMapper.writeValueAsString(problem));
        response.getWriter().flush();
    }

    private Map<String, Object> createProblemBody(
            HttpStatus status,
            String detail,
            String code,
            String instanceUri,
            long retryAfterSeconds) {
        Map<String, Object> problem = new LinkedHashMap<>();
        problem.put("type", "about:blank");
        problem.put("title", status.getReasonPhrase());
        problem.put("status", status.value());
        problem.put("detail", detail);
        if (instanceUri != null && !instanceUri.trim().isEmpty()) {
            problem.put("instance", instanceUri);
        }
        problem.put("code", code);
        if (status == HttpStatus.TOO_MANY_REQUESTS) {
            problem.put("retryAfterSeconds", Math.max(1L, retryAfterSeconds));
        }
        return problem;
    }

    private String resolveClientIp(HttpServletRequest request) {
        String remoteAddr = request.getRemoteAddr();
        if (remoteAddr == null || remoteAddr.trim().isEmpty()) {
            return "unknown";
        }
        return remoteAddr.trim();
    }

    private String extractRefreshCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (REFRESH_COOKIE_NAME.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }

    public record LockoutStatus(boolean locked, long retryAfterSeconds) {
    }

    private record RateLimitDecision(boolean allowed, long retryAfterSeconds) {
    }

    private static final class LocalWindowCounter {
        private volatile long expiresAtMillis;
        private final AtomicLong count = new AtomicLong(0L);

        private LocalWindowCounter(long expiresAtMillis) {
            this.expiresAtMillis = expiresAtMillis;
        }

        private synchronized long incrementAndGetIfValid(long now, long windowMillis) {
            if (now >= expiresAtMillis) {
                expiresAtMillis = now + windowMillis;
                count.set(1L);
                return 1L;
            }
            return count.incrementAndGet();
        }

        private synchronized long currentCount(long now) {
            if (now >= expiresAtMillis) {
                return 0L;
            }
            return count.get();
        }

        private boolean isExpired(long now) {
            return now >= expiresAtMillis;
        }

        private long remainingSeconds(long now) {
            long remainingMs = expiresAtMillis - now;
            if (remainingMs <= 0L) {
                return 1L;
            }
            return Math.max(1L, (remainingMs + 999L) / 1000L);
        }
    }
}
