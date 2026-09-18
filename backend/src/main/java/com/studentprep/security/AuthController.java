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

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    private final AuthenticationManager authenticationManager;
    private final JwtUtil jwtUtil;
    private final StringRedisTemplate redisTemplate;
    private final com.studentprep.student.StudentRepository studentRepository;
    private final com.studentprep.exam.ExamSessionRepository examSessionRepository;

    public AuthController(AuthenticationManager authenticationManager, JwtUtil jwtUtil, StringRedisTemplate redisTemplate,
                          com.studentprep.student.StudentRepository studentRepository,
                          com.studentprep.exam.ExamSessionRepository examSessionRepository) {
        this.authenticationManager = authenticationManager;
        this.jwtUtil = jwtUtil;
        this.redisTemplate = redisTemplate;
        this.studentRepository = studentRepository;
        this.examSessionRepository = examSessionRepository;
    }

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<Map<String, Object>>> login(@Valid @RequestBody LoginRequest request) {
        String cleanIdentifier = request.identifier() != null ? request.identifier().trim() : "";
        String cleanPin = request.pin() != null ? request.pin().trim() : "";

        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(cleanIdentifier, cleanPin)
        );

        java.util.Optional<com.studentprep.student.Student> studentOpt = studentRepository.findByRegistrationNumberIgnoreCase(cleanIdentifier);
        if (studentOpt.isPresent()) {
            com.studentprep.student.Student student = studentOpt.get();
            if (examSessionRepository.existsByUserIdAndStatus(student.getId(), "FLAGGED_TAB_SWITCH")) {
                throw new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.FORBIDDEN,
                        "Your account has been blocked due to exam malpractice. You cannot log in."
                );
            }
        }

        String role = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .findFirst()
                .orElse("ROLE_STUDENT");
                
        String jti = UUID.randomUUID().toString();
        String token = jwtUtil.generateToken(authentication.getName(), role, jti);
        String refreshToken = jwtUtil.generateRefreshToken(authentication.getName(), role, jti);
        
        redisTemplate.opsForValue().set("session:" + authentication.getName(), jti, Duration.ofDays(7));

        org.springframework.http.ResponseCookie cookie = org.springframework.http.ResponseCookie.from("refreshToken", refreshToken)
                .httpOnly(true)
                .secure(false)
                .sameSite("Lax")
                .path("/api/v1/auth/refresh")
                .maxAge(7 * 24 * 60 * 60)
                .build();

        return ResponseEntity.ok()
                .header(org.springframework.http.HttpHeaders.SET_COOKIE, cookie.toString())
                .body(ApiResponse.of(Map.of("accessToken", token, "expiresIn", 900, "role", role)));
    }

    @PostMapping("/refresh")
    public ResponseEntity<ApiResponse<Map<String, Object>>> refresh(@CookieValue(name = "refreshToken", required = false) String refreshToken) {
        if (refreshToken == null || !jwtUtil.isTokenValid(refreshToken)) {
            return ResponseEntity.status(401).build();
        }
        String identifier = jwtUtil.extractIdentifier(refreshToken);
        
        java.util.Optional<com.studentprep.student.Student> studentOpt = studentRepository.findByRegistrationNumberIgnoreCase(identifier);
        if (studentOpt.isPresent()) {
            com.studentprep.student.Student student = studentOpt.get();
            if (examSessionRepository.existsByUserIdAndStatus(student.getId(), "FLAGGED_TAB_SWITCH")) {
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
        return ResponseEntity.ok(ApiResponse.of(Map.of("accessToken", newAccessToken, "expiresIn", 900, "role", role)));
    }
}

