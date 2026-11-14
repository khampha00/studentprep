package com.studentprep.common;

import java.util.Locale;

/**
 * Canonical Redis key builders shared across Modulith modules so session locks,
 * exam unlock permits, and rate-limit buckets normalize identifiers consistently without cyclic dependencies.
 */
public final class SessionRedisKeys {

    public static final String SESSION_PREFIX = "session:";
    public static final String EXAM_UNLOCK_PREFIX = "exam_unlock:";
    public static final String LOGIN_FAIL_ID_PREFIX = "rate_limit:login:id:";
    public static final String LOGIN_IP_PREFIX = "rate_limit:login:ip:";
    public static final String REFRESH_IP_PREFIX = "rate_limit:refresh:ip:";
    public static final String REFRESH_ID_PREFIX = "rate_limit:refresh:id:";
    public static final String SYNC_PREFIX = "rate_limit:sync:";

    private SessionRedisKeys() {
    }

    public static String canonicalIdentifier(String identifier) {
        if (identifier == null || identifier.trim().isEmpty()) {
            throw new IllegalArgumentException("Identifier must not be blank");
        }
        return identifier.trim().toUpperCase(Locale.ROOT);
    }

    public static String sessionKey(String identifier) {
        return SESSION_PREFIX + canonicalIdentifier(identifier);
    }

    public static String examUnlockKey(String identifier) {
        return EXAM_UNLOCK_PREFIX + canonicalIdentifier(identifier);
    }

    public static String loginFailIdKey(String identifier) {
        return LOGIN_FAIL_ID_PREFIX + canonicalIdentifier(identifier);
    }

    public static String loginIpKey(String clientIp) {
        return LOGIN_IP_PREFIX + normalizeIp(clientIp);
    }

    public static String refreshIpKey(String clientIp) {
        return REFRESH_IP_PREFIX + normalizeIp(clientIp);
    }

    public static String refreshIdKey(String identifier) {
        return REFRESH_ID_PREFIX + canonicalIdentifier(identifier);
    }

    public static String syncKey(String identifier) {
        return SYNC_PREFIX + canonicalIdentifier(identifier);
    }

    private static String normalizeIp(String clientIp) {
        if (clientIp == null || clientIp.trim().isEmpty()) {
            throw new IllegalArgumentException("Client IP must not be blank");
        }
        return clientIp.trim().toLowerCase(Locale.ROOT);
    }
}
