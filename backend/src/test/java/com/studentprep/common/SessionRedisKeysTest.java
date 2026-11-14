package com.studentprep.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SessionRedisKeysTest {

    @Test
    void canonicalIdentifier_TrimsAndUppercasesLocaleIndependently() {
        assertEquals("JAMB-2026-X7Y8Z9", SessionRedisKeys.canonicalIdentifier("  jamb-2026-x7y8z9 "));
        assertEquals("ADMIN-001", SessionRedisKeys.canonicalIdentifier("admin-001"));
    }

    @Test
    void keyBuilders_ProduceCanonicalKeyFormats() {
        assertEquals("session:JAMB-2026-A1B2C3", SessionRedisKeys.sessionKey(" jamb-2026-a1b2c3 "));
        assertEquals("exam_unlock:JAMB-2026-A1B2C3", SessionRedisKeys.examUnlockKey("jamb-2026-a1b2c3"));
        assertEquals("rate_limit:login:id:ADMIN-001", SessionRedisKeys.loginFailIdKey("admin-001"));
        assertEquals("rate_limit:login:ip:192.168.1.50", SessionRedisKeys.loginIpKey(" 192.168.1.50 "));
        assertEquals("rate_limit:refresh:ip:2001:db8::1", SessionRedisKeys.refreshIpKey(" 2001:DB8::1 "));
        assertEquals("rate_limit:refresh:id:JAMB-2026-A1B2C3", SessionRedisKeys.refreshIdKey("jamb-2026-a1b2c3"));
        assertEquals("rate_limit:sync:JAMB-2026-A1B2C3", SessionRedisKeys.syncKey("jamb-2026-a1b2c3"));
    }

    @Test
    void keyBuilders_RejectNullOrBlankInputs() {
        assertThrows(IllegalArgumentException.class, () -> SessionRedisKeys.sessionKey(null));
        assertThrows(IllegalArgumentException.class, () -> SessionRedisKeys.sessionKey("   "));
        assertThrows(IllegalArgumentException.class, () -> SessionRedisKeys.examUnlockKey(""));
        assertThrows(IllegalArgumentException.class, () -> SessionRedisKeys.loginFailIdKey("  "));
        assertThrows(IllegalArgumentException.class, () -> SessionRedisKeys.loginIpKey(null));
        assertThrows(IllegalArgumentException.class, () -> SessionRedisKeys.refreshIpKey(" "));
        assertThrows(IllegalArgumentException.class, () -> SessionRedisKeys.syncKey(""));
    }
}
