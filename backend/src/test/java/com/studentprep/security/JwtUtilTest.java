package com.studentprep.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import io.jsonwebtoken.security.SignatureException;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JwtUtilTest {

    private static final String TEST_SECRET = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final String OTHER_SECRET = "fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210";

    @Test
    void accessTokenIncludesAccessTokenTypeAndExtractsClaims() {
        JwtUtil jwtUtil = new JwtUtil(TEST_SECRET, 900_000L, 604_800_000L);

        String token = jwtUtil.generateToken("JAMB2026-001", "ROLE_STUDENT", "jti-access-1");

        assertTrue(jwtUtil.isTokenValid(token));
        assertTrue(jwtUtil.isAccessTokenValid(token));
        assertFalse(jwtUtil.isRefreshTokenValid(token));
        assertEquals("JAMB2026-001", jwtUtil.extractIdentifier(token));
        assertEquals("ROLE_STUDENT", jwtUtil.extractRole(token));
        assertEquals("jti-access-1", jwtUtil.extractJti(token));
        assertEquals(JwtUtil.TOKEN_TYPE_ACCESS, jwtUtil.extractTokenType(token));
        assertEquals(900L, jwtUtil.getExpirationSeconds());
        assertEquals(604_800L, jwtUtil.getRefreshExpirationSeconds());
    }

    @Test
    void refreshTokenIncludesRefreshTokenTypeAndCannotBeUsedAsAccessToken() {
        JwtUtil jwtUtil = new JwtUtil(TEST_SECRET, 900_000L, 604_800_000L);

        String refreshToken = jwtUtil.generateRefreshToken("JAMB2026-001", "ROLE_STUDENT", "jti-refresh-1");

        assertTrue(jwtUtil.isTokenValid(refreshToken));
        assertTrue(jwtUtil.isRefreshTokenValid(refreshToken));
        assertFalse(jwtUtil.isAccessTokenValid(refreshToken));
        assertEquals(JwtUtil.TOKEN_TYPE_REFRESH, jwtUtil.extractTokenType(refreshToken));
        assertEquals("jti-refresh-1", jwtUtil.extractJti(refreshToken));
    }

    @Test
    void tokenWithoutTokenTypeClaimIsRejectedByStrictAccessAndRefreshChecks() {
        JwtUtil jwtUtil = new JwtUtil(TEST_SECRET, 900_000L, 604_800_000L);
        SecretKey key = Keys.hmacShaKeyFor(TEST_SECRET.getBytes(StandardCharsets.UTF_8));
        String legacyToken = Jwts.builder()
                .id("jti-legacy")
                .subject("ADMIN-001")
                .claim("role", "ROLE_ADMIN")
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 60_000L))
                .signWith(key)
                .compact();

        assertFalse(jwtUtil.isAccessTokenValid(legacyToken));
        assertFalse(jwtUtil.isRefreshTokenValid(legacyToken));
    }

    @Test
    void expiredTokenSignedWithValidKeyAllowsVerifiedClaimExtraction() {
        JwtUtil jwtUtil = new JwtUtil(TEST_SECRET, -60_000L, -60_000L);
        String expiredAccess = jwtUtil.generateToken("JAMB2026-002", "ROLE_STUDENT", "jti-exp-1");

        assertFalse(jwtUtil.isTokenValid(expiredAccess));
        assertFalse(jwtUtil.isAccessTokenValid(expiredAccess));

        Claims claims = jwtUtil.extractClaimsAllowExpired(expiredAccess);
        assertEquals("JAMB2026-002", claims.getSubject());
        assertEquals("jti-exp-1", claims.getId());
        assertEquals("ROLE_STUDENT", claims.get("role", String.class));
        assertEquals(JwtUtil.TOKEN_TYPE_ACCESS, claims.get(JwtUtil.TOKEN_TYPE_CLAIM, String.class));
        assertEquals("JAMB2026-002", jwtUtil.extractIdentifierAllowExpired(expiredAccess));
        assertEquals("jti-exp-1", jwtUtil.extractJtiAllowExpired(expiredAccess));
    }

    @Test
    void expiredTokenWithWrongKeyOrTamperedPayloadThrowsSignatureException() {
        JwtUtil validUtil = new JwtUtil(TEST_SECRET, -60_000L, -60_000L);
        JwtUtil attackerUtil = new JwtUtil(OTHER_SECRET, -60_000L, -60_000L);

        String wrongKeyExpired = attackerUtil.generateToken("ADMIN", "ROLE_ADMIN", "forged-jti");
        assertThrows(SignatureException.class, () -> validUtil.extractClaimsAllowExpired(wrongKeyExpired));
        assertThrows(JwtException.class, () -> validUtil.extractIdentifierAllowExpired(wrongKeyExpired));
        assertThrows(JwtException.class, () -> validUtil.extractJtiAllowExpired(wrongKeyExpired));

        String validlySignedExpired = validUtil.generateToken("STUDENT-1", "ROLE_STUDENT", "jti-1");
        String[] parts = validlySignedExpired.split("\\.");
        String tampered = parts[0] + "." + parts[1] + "X." + parts[2];
        assertThrows(JwtException.class, () -> validUtil.extractClaimsAllowExpired(tampered));
    }

    @Test
    void nullBlankAndMalformedTokensFailValidationSafely() {
        JwtUtil jwtUtil = new JwtUtil(TEST_SECRET, 900_000L, 604_800_000L);

        assertFalse(jwtUtil.isAccessTokenValid(null));
        assertFalse(jwtUtil.isAccessTokenValid("   "));
        assertFalse(jwtUtil.isAccessTokenValid("not.a.jwt"));
        assertFalse(jwtUtil.isRefreshTokenValid(null));
        assertFalse(jwtUtil.isRefreshTokenValid(""));
        assertFalse(jwtUtil.isRefreshTokenValid("not.a.jwt"));
    }
}
