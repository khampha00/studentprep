package com.studentprep.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PinHashingPasswordEncoderTest {

    private final PinHashingPasswordEncoder encoder = new PinHashingPasswordEncoder();

    @Test
    void encode_ProducesArgon2idHashMatchingSpring58DefaultsAndVerifies() {
        String encoded = encoder.encode("49281");

        assertTrue(encoded.startsWith("$argon2id$v=19$m=16384,t=2,p=1$"));
        assertNotEquals("49281", encoded);
        assertTrue(encoder.matches("49281", encoded));
        assertFalse(encoder.matches("12345", encoded));
        assertFalse(encoder.upgradeEncoding(encoded));
        assertTrue(encoder.isSupportedHash(encoded));
    }

    @Test
    void matches_VerifiesV2SeedAdminArgon2idHash() {
        String v2SeedHash = "$argon2id$v=19$m=16384,t=2,p=1$rqXBKJfgPQ9gL6sh+pULog$80vfQbWJEHWH3ehXW4YmhHZN8HGNDR/i9PsKYdA87bU";

        assertTrue(encoder.matches("admin123", v2SeedHash));
        assertFalse(encoder.matches("12345", v2SeedHash));
        assertFalse(encoder.upgradeEncoding(v2SeedHash));
    }

    @Test
    void matches_SupportsLegacyBcryptPrefixesAndMarksForUpgrade() {
        BCryptPasswordEncoder bcrypt2a = new BCryptPasswordEncoder(BCryptPasswordEncoder.BCryptVersion.$2A, 4);
        BCryptPasswordEncoder bcrypt2b = new BCryptPasswordEncoder(BCryptPasswordEncoder.BCryptVersion.$2B, 4);
        BCryptPasswordEncoder bcrypt2y = new BCryptPasswordEncoder(BCryptPasswordEncoder.BCryptVersion.$2Y, 4);

        String hash2a = bcrypt2a.encode("73914");
        String hash2b = bcrypt2b.encode("73914");
        String hash2y = bcrypt2y.encode("73914");

        assertTrue(encoder.matches("73914", hash2a));
        assertTrue(encoder.matches("73914", hash2b));
        assertTrue(encoder.matches("73914", hash2y));
        assertFalse(encoder.matches("00000", hash2b));

        assertTrue(encoder.upgradeEncoding(hash2a));
        assertTrue(encoder.upgradeEncoding(hash2b));
        assertTrue(encoder.upgradeEncoding(hash2y));
        assertTrue(encoder.isSupportedHash(hash2b));
    }

    @Test
    void upgradeEncoding_ReturnsTrueForWeakerArgon2Parameters() {
        Argon2PasswordEncoder weakArgon2 = new Argon2PasswordEncoder(16, 32, 1, 4096, 3);
        String weakHash = weakArgon2.encode("58204");

        assertTrue(encoder.matches("58204", weakHash));
        assertTrue(encoder.upgradeEncoding(weakHash));
    }

    @Test
    void matchesAndUpgradeEncoding_RejectPlaintextSentinelsAndBlankValues() {
        assertFalse(encoder.matches("12345", "12345"));
        assertFalse(encoder.matches("admin123", "{noop}admin123"));
        assertFalse(encoder.matches("!LEGACY_INVALIDATED!", "!LEGACY_INVALIDATED!"));
        assertFalse(encoder.matches("!ADMIN_BOOTSTRAP_REQUIRED!", "!ADMIN_BOOTSTRAP_REQUIRED!"));
        assertFalse(encoder.matches("!LOCKED!", "!LOCKED!"));
        assertFalse(encoder.matches("", "$argon2id$v=19$m=16384,t=2,p=1$rqXBKJfgPQ9gL6sh+pULog$80vfQbWJEHWH3ehXW4YmhHZN8HGNDR/i9PsKYdA87bU"));
        assertFalse(encoder.matches(null, "$argon2id$v=19$m=16384,t=2,p=1$rqXBKJfgPQ9gL6sh+pULog$80vfQbWJEHWH3ehXW4YmhHZN8HGNDR/i9PsKYdA87bU"));
        assertFalse(encoder.matches("49281", null));
        assertFalse(encoder.matches("49281", "   "));
        assertFalse(encoder.matches("49281", "$argon2i$v=19$m=16384,t=2,p=1$invalid"));
        assertFalse(encoder.matches("49281", "$2a$10$malformed"));

        assertFalse(encoder.upgradeEncoding("12345"));
        assertFalse(encoder.upgradeEncoding("!LEGACY_INVALIDATED!"));
        assertFalse(encoder.upgradeEncoding("!ADMIN_BOOTSTRAP_REQUIRED!"));
        assertFalse(encoder.upgradeEncoding("!LOCKED!"));
        assertFalse(encoder.upgradeEncoding(""));
        assertFalse(encoder.upgradeEncoding(null));
        assertFalse(encoder.upgradeEncoding("$2a$10$malformed"));

        assertFalse(encoder.isSupportedHash("12345"));
        assertFalse(encoder.isSupportedHash("!LEGACY_INVALIDATED!"));
        assertFalse(encoder.isSupportedHash("!ADMIN_BOOTSTRAP_REQUIRED!"));
        assertFalse(encoder.isSupportedHash("!LOCKED!"));
    }

    @Test
    void encode_RejectsNullOrBlankCredentials() {
        assertThrows(IllegalArgumentException.class, () -> encoder.encode(null));
        assertThrows(IllegalArgumentException.class, () -> encoder.encode(""));
        assertThrows(IllegalArgumentException.class, () -> encoder.encode("   "));
    }
}
