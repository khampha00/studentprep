package com.studentprep.security;

import com.studentprep.common.ApiResponse;
import com.studentprep.common.SessionRedisKeys;
import com.studentprep.exam.ExamInternalAPI;
import com.studentprep.security.dto.LoginRequest;
import com.studentprep.security.dto.LoginResponse;
import com.studentprep.student.Student;
import com.studentprep.student.StudentInternalAPI;
import com.studentprep.student.User;
import com.studentprep.student.UserRepository;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);
    private static final String ROLE_STUDENT = "ROLE_STUDENT";
    private static final String AUTH_COOKIE_PATH = "/api/v1/auth";

    private final AuthenticationManager authenticationManager;
    private final CustomUserDetailsService userDetailsService;
    private final JwtUtil jwtUtil;
    private final StringRedisTemplate redisTemplate;
    private final StudentInternalAPI studentInternalAPI;
    private final UserRepository userRepository;
    private final ExamInternalAPI examInternalAPI;
    private final RateLimitInterceptor rateLimitInterceptor;
    private final boolean cookieSecure;
    private final String cookieSameSite;

    public AuthController(
            AuthenticationManager authenticationManager,
            CustomUserDetailsService userDetailsService,
            JwtUtil jwtUtil,
            StringRedisTemplate redisTemplate,
            StudentInternalAPI studentInternalAPI,
            UserRepository userRepository,
            ExamInternalAPI examInternalAPI,
            RateLimitInterceptor rateLimitInterceptor,
            @Value("${security.cookie.secure:false}") boolean cookieSecure,
            @Value("${security.cookie.same-site:Strict}") String cookieSameSite) {
        this.authenticationManager = authenticationManager;
        this.userDetailsService = userDetailsService;
        this.jwtUtil = jwtUtil;
        this.redisTemplate = redisTemplate;
        this.studentInternalAPI = studentInternalAPI;
        this.userRepository = userRepository;
        this.examInternalAPI = examInternalAPI;
        this.rateLimitInterceptor = rateLimitInterceptor;
        this.cookieSecure = cookieSecure;
        this.cookieSameSite = cookieSameSite;
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(
            @Valid @RequestBody LoginRequest request,
            @CookieValue(name = RateLimitInterceptor.REFRESH_COOKIE_NAME, required = false) String existingRefreshToken,
            HttpServletRequest httpRequest) {
        String cleanIdentifier = request.identifier() != null ? request.identifier().trim() : "";
        String cleanPin = request.pin() != null ? request.pin().trim() : "";
        String canonicalIdentifier = SessionRedisKeys.canonicalIdentifier(cleanIdentifier);

        RateLimitInterceptor.LockoutStatus lockout = rateLimitInterceptor.checkLoginIdentifierLockout(canonicalIdentifier);
        if (lockout.locked()) {
            long retryAfter = Math.max(1L, lockout.retryAfterSeconds());
            Map<String, Object> body = rateLimitInterceptor.buildRateLimitProblem(
                    "Too many failed login attempts. Please try again later.",
                    httpRequest != null ? httpRequest.getRequestURI() : "/api/v1/auth/login",
                    retryAfter);
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .header(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfter))
                    .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                    .body(body);
        }

        AuthenticatedUserDetails userDetails;
        try {
            userDetails = userDetailsService.loadUserByUsername(cleanIdentifier);
        } catch (UsernameNotFoundException ex) {
            rateLimitInterceptor.recordLoginFailure(canonicalIdentifier);
            throw new BadCredentialsException("Invalid credentials", ex);
        }

        if (userDetails.isPinResetRequired() && isSentinelCredential(userDetails.getPassword())) {
            throw problemException(
                    HttpStatus.FORBIDDEN,
                    "Your PIN has been invalidated and must be reset by an administrator.",
                    "PIN_RESET_REQUIRED");
        }

        try {
            authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(canonicalIdentifier, cleanPin));
        } catch (AuthenticationException ex) {
            rateLimitInterceptor.recordLoginFailure(canonicalIdentifier);
            throw ex;
        }

        if (userDetails.isPinResetRequired()) {
            throw problemException(
                    HttpStatus.FORBIDDEN,
                    "Your PIN has been invalidated and must be reset by an administrator.",
                    "PIN_RESET_REQUIRED");
        }

        rateLimitInterceptor.clearLoginFailures(canonicalIdentifier);

        String role = userDetails.getRole();
        boolean isStudent = ROLE_STUDENT.equals(role);
        boolean hasActiveExam = false;
        if (isStudent) {
            Student student = studentInternalAPI.findById(userDetails.getUserId())
                    .or(() -> studentInternalAPI.findByRegistrationNumberIgnoreCase(canonicalIdentifier))
                    .orElseThrow(() -> new BadCredentialsException("Student account not found"));

            if (examInternalAPI.hasFlaggedSession(student.getId())) {
                throw problemException(
                        HttpStatus.FORBIDDEN,
                        "Your account has been blocked due to exam malpractice. You cannot log in.",
                        "EXAM_MALPRACTICE_BLOCKED");
            }
            hasActiveExam = examInternalAPI.hasActiveSession(student.getId());
        } else {
            Optional<Student> studentOpt = studentInternalAPI.findByRegistrationNumberIgnoreCase(canonicalIdentifier);
            if (studentOpt.isPresent()) {
                Student student = studentOpt.get();
                if (examInternalAPI.hasFlaggedSession(student.getId())) {
                    throw problemException(
                            HttpStatus.FORBIDDEN,
                            "Your account has been blocked due to exam malpractice. You cannot log in.",
                            "EXAM_MALPRACTICE_BLOCKED");
                }
                hasActiveExam = examInternalAPI.hasActiveSession(student.getId());
            }
        }

        String sessionKey = SessionRedisKeys.sessionKey(canonicalIdentifier);
        String unlockKey = SessionRedisKeys.examUnlockKey(canonicalIdentifier);
        String newJti = UUID.randomUUID().toString();
        Duration sessionTtl = Duration.ofSeconds(jwtUtil.getRefreshExpirationSeconds());

        try {
            boolean isSameDevice = isVerifiedSameWorkstation(existingRefreshToken, canonicalIdentifier, sessionKey);
            if (isSameDevice) {
                redisTemplate.opsForValue().set(sessionKey, newJti, sessionTtl);
                redisTemplate.delete(unlockKey);
            } else if (Boolean.TRUE.equals(redisTemplate.delete(unlockKey))) {
                // Consume the single-use admin unlock permit so the candidate can bind once on recovery.
                redisTemplate.opsForValue().set(sessionKey, newJti, sessionTtl);
            } else if (hasActiveExam) {
                throw problemException(
                        HttpStatus.CONFLICT,
                        "Only one session allowed. You already have an active examination in progress on another device.",
                        "ACTIVE_EXAM_LOCKED");
            } else {
                Boolean created = redisTemplate.opsForValue().setIfAbsent(sessionKey, newJti, sessionTtl);
                if (!Boolean.TRUE.equals(created)) {
                    throw problemException(
                            HttpStatus.CONFLICT,
                            "Only one session allowed. This account is already logged in on another device. Please log out first.",
                            "CONCURRENT_SESSION_CONFLICT");
                }
            }
        } catch (DataAccessException ex) {
            log.error("Redis session store failure during login for {}", canonicalIdentifier, ex);
            throw problemException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "Session store temporarily unavailable",
                    "SESSION_STORE_UNAVAILABLE");
        }

        String accessToken = jwtUtil.generateToken(canonicalIdentifier, role, newJti);
        String refreshToken = jwtUtil.generateRefreshToken(canonicalIdentifier, role, newJti);
        ResponseCookie cookie = buildRefreshCookie(refreshToken, jwtUtil.getRefreshExpirationSeconds());

        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .body(ApiResponse.of(new LoginResponse(accessToken, jwtUtil.getExpirationSeconds(), role)));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authHeader,
            @CookieValue(name = RateLimitInterceptor.REFRESH_COOKIE_NAME, required = false) String refreshToken) {
        boolean revoked = false;
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String accessToken = authHeader.substring(7).trim();
            revoked = revokeVerifiedAccessSession(accessToken);
        }
        if (!revoked && refreshToken != null && !refreshToken.isBlank()) {
            revokeVerifiedRefreshSession(refreshToken);
        }

        ResponseCookie expiredCookie = buildRefreshCookie("", 0L);
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, expiredCookie.toString())
                .build();
    }

    @PostMapping("/refresh")
    public ResponseEntity<ApiResponse<LoginResponse>> refresh(
            @CookieValue(name = RateLimitInterceptor.REFRESH_COOKIE_NAME, required = false) String refreshToken) {
        if (refreshToken == null || !jwtUtil.isRefreshTokenValid(refreshToken)) {
            throw problemException(
                    HttpStatus.UNAUTHORIZED,
                    "Invalid or expired refresh token",
                    "INVALID_REFRESH_TOKEN");
        }

        String rawIdentifier = jwtUtil.extractIdentifier(refreshToken);
        String role = jwtUtil.extractRole(refreshToken);
        String jti = jwtUtil.extractJti(refreshToken);
        if (isBlank(rawIdentifier) || isBlank(role) || isBlank(jti)) {
            throw problemException(
                    HttpStatus.UNAUTHORIZED,
                    "Invalid refresh token claims",
                    "INVALID_REFRESH_TOKEN");
        }

        String canonicalIdentifier = SessionRedisKeys.canonicalIdentifier(rawIdentifier);
        String sessionKey = SessionRedisKeys.sessionKey(canonicalIdentifier);

        if (ROLE_STUDENT.equals(role)) {
            Student student = studentInternalAPI.findByRegistrationNumberIgnoreCase(canonicalIdentifier)
                    .orElseThrow(() -> problemException(
                            HttpStatus.UNAUTHORIZED,
                            "Student account no longer exists",
                            "INVALID_REFRESH_SESSION"));
            if (student.isPinResetRequired()) {
                safeDeleteKey(sessionKey);
                throw problemException(
                        HttpStatus.UNAUTHORIZED,
                        "Credentials invalidated and must be reset by an administrator",
                        "PIN_RESET_REQUIRED");
            }
            if (examInternalAPI.hasFlaggedSession(student.getId())) {
                safeDeleteKey(sessionKey);
                throw problemException(
                        HttpStatus.FORBIDDEN,
                        "Your account has been blocked due to exam malpractice.",
                        "EXAM_MALPRACTICE_BLOCKED");
            }
        } else {
            User user = userRepository.findByIdentifierIgnoreCase(canonicalIdentifier)
                    .orElseThrow(() -> problemException(
                            HttpStatus.UNAUTHORIZED,
                            "User account no longer exists",
                            "INVALID_REFRESH_SESSION"));
            if (user.isPinResetRequired()) {
                safeDeleteKey(sessionKey);
                throw problemException(
                        HttpStatus.UNAUTHORIZED,
                        "Credentials invalidated and must be reset by an administrator",
                        "PIN_RESET_REQUIRED");
            }
        }

        try {
            String storedJti = redisTemplate.opsForValue().get(sessionKey);
            if (storedJti == null || !storedJti.equals(jti)) {
                throw problemException(
                        HttpStatus.UNAUTHORIZED,
                        "Session active on another device",
                        "SESSION_EVICTED");
            }

            String newAccessToken = jwtUtil.generateToken(canonicalIdentifier, role, jti);
            redisTemplate.expire(sessionKey, Duration.ofSeconds(jwtUtil.getRefreshExpirationSeconds()));
            return ResponseEntity.ok(ApiResponse.of(
                    new LoginResponse(newAccessToken, jwtUtil.getExpirationSeconds(), role)));
        } catch (DataAccessException ex) {
            log.error("Redis session store failure during refresh for {}", canonicalIdentifier, ex);
            throw problemException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "Session store temporarily unavailable",
                    "SESSION_STORE_UNAVAILABLE");
        }
    }

    private boolean isVerifiedSameWorkstation(String existingRefreshToken, String canonicalIdentifier, String sessionKey) {
        if (existingRefreshToken == null || !jwtUtil.isRefreshTokenValid(existingRefreshToken)) {
            return false;
        }
        String tokenIdentifier = jwtUtil.extractIdentifier(existingRefreshToken);
        String oldJti = jwtUtil.extractJti(existingRefreshToken);
        if (isBlank(tokenIdentifier) || isBlank(oldJti)) {
            return false;
        }
        if (!canonicalIdentifier.equals(SessionRedisKeys.canonicalIdentifier(tokenIdentifier))) {
            return false;
        }
        String storedJti = redisTemplate.opsForValue().get(sessionKey);
        return storedJti != null && storedJti.equals(oldJti);
    }

    private boolean revokeVerifiedAccessSession(String accessToken) {
        if (accessToken.isBlank()) {
            return false;
        }
        try {
            Claims claims = jwtUtil.extractClaimsAllowExpired(accessToken);
            String tokenType = claims.get(JwtUtil.TOKEN_TYPE_CLAIM, String.class);
            if (!JwtUtil.TOKEN_TYPE_ACCESS.equals(tokenType)) {
                return false;
            }
            return revokeIfMatchingJti(claims.getSubject(), claims.getId());
        } catch (JwtException | IllegalArgumentException ex) {
            return false;
        }
    }

    private boolean revokeVerifiedRefreshSession(String refreshToken) {
        if (!jwtUtil.isRefreshTokenValid(refreshToken)) {
            return false;
        }
        return revokeIfMatchingJti(jwtUtil.extractIdentifier(refreshToken), jwtUtil.extractJti(refreshToken));
    }

    private boolean revokeIfMatchingJti(String rawIdentifier, String presentedJti) {
        if (isBlank(rawIdentifier) || isBlank(presentedJti)) {
            return false;
        }
        String sessionKey = SessionRedisKeys.sessionKey(rawIdentifier);
        try {
            String storedJti = redisTemplate.opsForValue().get(sessionKey);
            if (storedJti != null && storedJti.equals(presentedJti)) {
                redisTemplate.delete(sessionKey);
                return true;
            }
        } catch (DataAccessException ex) {
            log.warn("Redis unavailable during logout session check for {}", sessionKey, ex);
        }
        return false;
    }

    private void safeDeleteKey(String key) {
        try {
            redisTemplate.delete(key);
        } catch (DataAccessException ex) {
            log.warn("Failed to delete Redis key {}", key, ex);
        }
    }

    private ResponseCookie buildRefreshCookie(String value, long maxAgeSeconds) {
        return ResponseCookie.from(RateLimitInterceptor.REFRESH_COOKIE_NAME, value)
                .httpOnly(true)
                .secure(cookieSecure)
                .sameSite(cookieSameSite)
                .path(AUTH_COOKIE_PATH)
                .maxAge(Math.max(0L, maxAgeSeconds))
                .build();
    }

    private static ResponseStatusException problemException(HttpStatus status, String detail, String code) {
        ResponseStatusException ex = new ResponseStatusException(status, detail);
        ex.getBody().setProperty("code", code);
        return ex;
    }

    private static boolean isSentinelCredential(String pinHash) {
        return pinHash == null || pinHash.isBlank() || pinHash.startsWith("!");
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
