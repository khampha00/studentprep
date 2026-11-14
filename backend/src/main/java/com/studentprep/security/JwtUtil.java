package com.studentprep.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.security.Keys;
import io.jsonwebtoken.security.SecurityException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

@Component
public class JwtUtil {

    public static final String TOKEN_TYPE_CLAIM = "token_type";
    public static final String TOKEN_TYPE_ACCESS = "access";
    public static final String TOKEN_TYPE_REFRESH = "refresh";

    private static final Logger log = LoggerFactory.getLogger(JwtUtil.class);

    private final long expirationMs;
    private final long refreshExpirationMs;
    private final SecretKey signingKey;
    private final JwtParser jwtParser;

    public JwtUtil(
            @Value("${security.jwt.secret}") String secret,
            @Value("${security.jwt.expiration-ms}") long expirationMs,
            @Value("${security.jwt.refresh-expiration-ms:604800000}") long refreshExpirationMs) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("security.jwt.secret must not be blank");
        }
        this.expirationMs = expirationMs;
        this.refreshExpirationMs = refreshExpirationMs;
        this.signingKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.jwtParser = Jwts.parser().verifyWith(this.signingKey).build();
    }

    public String generateToken(String identifier, String role, String jti) {
        Date now = new Date();
        return Jwts.builder()
                .id(jti)
                .subject(identifier)
                .claim("role", role)
                .claim(TOKEN_TYPE_CLAIM, TOKEN_TYPE_ACCESS)
                .issuedAt(now)
                .expiration(new Date(now.getTime() + expirationMs))
                .signWith(signingKey)
                .compact();
    }

    public String generateRefreshToken(String identifier, String role, String jti) {
        Date now = new Date();
        return Jwts.builder()
                .id(jti)
                .subject(identifier)
                .claim("role", role)
                .claim(TOKEN_TYPE_CLAIM, TOKEN_TYPE_REFRESH)
                .issuedAt(now)
                .expiration(new Date(now.getTime() + refreshExpirationMs))
                .signWith(signingKey)
                .compact();
    }

    public String extractIdentifier(String token) {
        return getClaims(token).getSubject();
    }

    /**
     * Extracts signature-verified claims from a JWT without enforcing expiry.
     * Used on logout so an already-expired access token with a valid HMAC signature
     * can still identify and delete the active Redis session key.
     */
    public Claims extractClaimsAllowExpired(String token) {
        try {
            return getClaims(token);
        } catch (ExpiredJwtException e) {
            return e.getClaims();
        }
    }

    /**
     * Extracts the subject (identifier) from a JWT without enforcing expiry.
     * This is used by the logout endpoint so that an already-expired access token
     * can still be used to identify and delete the Redis session key.
     */
    public String extractIdentifierAllowExpired(String token) {
        return extractClaimsAllowExpired(token).getSubject();
    }

    public String extractJtiAllowExpired(String token) {
        return extractClaimsAllowExpired(token).getId();
    }

    public String extractRole(String token) {
        return getClaims(token).get("role", String.class);
    }

    public String extractJti(String token) {
        return getClaims(token).getId();
    }

    public String extractTokenType(String token) {
        return getClaims(token).get(TOKEN_TYPE_CLAIM, String.class);
    }

    public boolean isTokenValid(String token) {
        return parseValidClaims(token) != null;
    }

    public boolean isAccessTokenValid(String token) {
        Claims claims = parseValidClaims(token);
        return claims != null && TOKEN_TYPE_ACCESS.equals(claims.get(TOKEN_TYPE_CLAIM, String.class));
    }

    public boolean isRefreshTokenValid(String token) {
        Claims claims = parseValidClaims(token);
        return claims != null && TOKEN_TYPE_REFRESH.equals(claims.get(TOKEN_TYPE_CLAIM, String.class));
    }

    public long getExpirationSeconds() {
        return Math.max(0L, expirationMs / 1000L);
    }

    public long getRefreshExpirationSeconds() {
        return Math.max(0L, refreshExpirationMs / 1000L);
    }

    private Claims parseValidClaims(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        try {
            return getClaims(token);
        } catch (ExpiredJwtException e) {
            log.warn("JWT token expired: {}", e.getMessage());
            return null;
        } catch (SecurityException | MalformedJwtException e) {
            log.warn("Invalid JWT token: {}", e.getMessage());
            return null;
        } catch (JwtException | IllegalArgumentException e) {
            log.warn("Rejected JWT token: {}", e.getMessage());
            return null;
        } catch (Exception e) {
            log.error("Unexpected JWT validation error", e);
            return null;
        }
    }

    private Claims getClaims(String token) {
        return jwtParser.parseSignedClaims(token).getPayload();
    }
}
