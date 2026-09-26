package com.studentprep.security;
import com.studentprep.security.dto.LoginRequest;
import com.studentprep.security.dto.LoginResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.*;
import jakarta.validation.Valid;
import java.util.Map;
import java.util.UUID;
import java.time.Duration;
import com.studentprep.common.ApiResponse;
import org.springframework.data.redis.core.StringRedisTemplate;
import com.studentprep.student.StudentInternalAPI;
import com.studentprep.exam.ExamInternalAPI;
import com.studentprep.student.Student;
import java.util.Optional;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.http.ResponseCookie;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpHeaders;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    private final AuthenticationManager authenticationManager;
    private final JwtUtil jwtUtil;
    private final StringRedisTemplate redisTemplate;
    private final StudentInternalAPI studentInternalAPI;
    private final ExamInternalAPI examInternalAPI;

    public AuthController(AuthenticationManager authenticationManager, JwtUtil jwtUtil, StringRedisTemplate redisTemplate,
                          StudentInternalAPI studentInternalAPI,
                          ExamInternalAPI examInternalAPI) {
        this.authenticationManager = authenticationManager;
        this.jwtUtil = jwtUtil;
        this.redisTemplate = redisTemplate;
        this.studentInternalAPI = studentInternalAPI;
        this.examInternalAPI = examInternalAPI;
    }

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<Map<String, Object>>> login(
            @Valid @RequestBody LoginRequest request,
            @CookieValue(name = "refreshToken", required = false) String existingRefreshToken) {
        String cleanIdentifier = request.identifier() != null ? request.identifier().trim() : "";
        String cleanPin = request.pin() != null ? request.pin().trim() : "";

        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(cleanIdentifier, cleanPin)
        );

        Optional<Student> studentOpt = studentInternalAPI.findByRegistrationNumberIgnoreCase(cleanIdentifier);
        if (studentOpt.isPresent()) {
            Student student = studentOpt.get();
            if (examInternalAPI.hasFlaggedSession(student.getId())) {
                throw new ResponseStatusException(
                        HttpStatus.FORBIDDEN,
                        "Your account has been blocked due to exam malpractice. You cannot log in."
                );
            }
            if (examInternalAPI.hasActiveSession(student.getId())) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "Only one session allowed. You already have an active examination in progress on another device."
                );
            }
        }

        String role = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .findFirst()
                .orElse("ROLE_STUDENT");
                
        // Determine if they are logging in from the same exact device/browser
        boolean isSameDevice = false;
        if (existingRefreshToken != null && jwtUtil.isTokenValid(existingRefreshToken)) {
            try {
                String oldJti = jwtUtil.extractJti(existingRefreshToken);
                String storedJti = redisTemplate.opsForValue().get("session:" + authentication.getName());
                if (storedJti != null && storedJti.equals(oldJti)) {
                    isSameDevice = true;
                }
            } catch (Exception ignored) {
            }
        }

        String jti = UUID.randomUUID().toString();
        String token = jwtUtil.generateToken(authentication.getName(), role, jti);
        String refreshToken = jwtUtil.generateRefreshToken(authentication.getName(), role, jti);

        if (isSameDevice) {
            redisTemplate.opsForValue().set("session:" + authentication.getName(), jti, Duration.ofDays(7));
        } else {
            Boolean sessionCreated = redisTemplate.opsForValue().setIfAbsent("session:" + authentication.getName(), jti, Duration.ofDays(7));
            if (Boolean.FALSE.equals(sessionCreated)) {
                throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Only one session allowed. This account is already logged in on another device. Please log out first."
                );
            }
        }

        ResponseCookie cookie = ResponseCookie.from("refreshToken", refreshToken)
                .httpOnly(true)
                .secure(false)
                .sameSite("Lax")
                .path("/api/v1/auth/refresh")
                .maxAge(7 * 24 * 60 * 60)
                .build();

        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .body(ApiResponse.of(Map.of("accessToken", token, "expiresIn", 900, "role", role)));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@RequestHeader(value = "Authorization", required = false) String authHeader) {
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7);
            try {
                String identifier = jwtUtil.extractIdentifierAllowExpired(token);
                redisTemplate.delete("session:" + identifier);
            } catch (Exception ignored) {
                // If token is malformed/expired, still expire the cookie
            }
        }
        ResponseCookie expiredCookie = ResponseCookie.from("refreshToken", "")
                .httpOnly(true)
                .secure(false)
                .sameSite("Lax")
                .path("/api/v1/auth/refresh")
                .maxAge(0)
                .build();
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, expiredCookie.toString())
                .build();
    }

    @PostMapping("/refresh")
    public ResponseEntity<ApiResponse<Map<String, Object>>> refresh(@CookieValue(name = "refreshToken", required = false) String refreshToken) {
        if (refreshToken == null || !jwtUtil.isTokenValid(refreshToken)) {
            return ResponseEntity.status(401).build();
        }
        String identifier = jwtUtil.extractIdentifier(refreshToken);
        
        java.util.Optional<com.studentprep.student.Student> studentOpt = studentInternalAPI.findByRegistrationNumberIgnoreCase(identifier);
        if (studentOpt.isPresent()) {
            com.studentprep.student.Student student = studentOpt.get();
            if (examInternalAPI.hasFlaggedSession(student.getId())) {
                return ResponseEntity.status(403).build();
            }
        }

        String role = jwtUtil.extractRole(refreshToken);
        String jti = jwtUtil.extractJti(refreshToken);
        
        String storedJti = redisTemplate.opsForValue().get("session:" + identifier);
        if (storedJti == null || !storedJti.equals(jti)) {
            return ResponseEntity.status(401).build();
        }
        
        String newAccessToken = jwtUtil.generateToken(identifier, role, jti);
        redisTemplate.expire("session:" + identifier, Duration.ofDays(7));
        return ResponseEntity.ok(ApiResponse.of(Map.of("accessToken", newAccessToken, "expiresIn", 900, "role", role)));
    }
}

