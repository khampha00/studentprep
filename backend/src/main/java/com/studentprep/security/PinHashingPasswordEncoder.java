package com.studentprep.security;

import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Adaptive credential encoder that hashes new PINs and passwords exclusively with Argon2id
 * while verifying both Argon2id and legacy BCrypt hashes and rejecting plaintext or sentinel markers.
 */
public class PinHashingPasswordEncoder implements PasswordEncoder {

    private static final String ARGON2ID_PREFIX = "$argon2id$";
    private static final String BCRYPT_2A_PREFIX = "$2a$";
    private static final String BCRYPT_2B_PREFIX = "$2b$";
    private static final String BCRYPT_2Y_PREFIX = "$2y$";
    private static final Pattern BCRYPT_HASH_PATTERN =
            Pattern.compile("\\A\\$2[aby]\\$\\d{2}\\$[./0-9A-Za-z]{53}\\z");
    private static final Set<String> SENTINEL_MARKERS = Set.of(
            "!LEGACY_INVALIDATED!",
            "!ADMIN_BOOTSTRAP_REQUIRED!",
            "!LOCKED!"
    );

    private final Argon2PasswordEncoder argon2Encoder;
    private final BCryptPasswordEncoder bcryptEncoder;

    public PinHashingPasswordEncoder() {
        this(Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8(), new BCryptPasswordEncoder());
    }

    PinHashingPasswordEncoder(Argon2PasswordEncoder argon2Encoder, BCryptPasswordEncoder bcryptEncoder) {
        this.argon2Encoder = Objects.requireNonNull(argon2Encoder, "argon2Encoder must not be null");
        this.bcryptEncoder = Objects.requireNonNull(bcryptEncoder, "bcryptEncoder must not be null");
    }

    @Override
    public String encode(CharSequence rawPassword) {
        if (rawPassword == null || rawPassword.toString().isBlank()) {
            throw new IllegalArgumentException("Credential must not be null or blank");
        }
        return argon2Encoder.encode(rawPassword);
    }

    @Override
    public boolean matches(CharSequence rawPassword, String encodedPassword) {
        if (rawPassword == null || rawPassword.toString().isBlank() || isNonHashOrSentinel(encodedPassword)) {
            return false;
        }
        if (encodedPassword.startsWith(ARGON2ID_PREFIX)) {
            try {
                return argon2Encoder.matches(rawPassword, encodedPassword);
            } catch (IllegalArgumentException ex) {
                return false;
            }
        }
        if (isValidBcryptFormat(encodedPassword)) {
            try {
                return bcryptEncoder.matches(rawPassword, encodedPassword);
            } catch (IllegalArgumentException ex) {
                return false;
            }
        }
        return false;
    }

    @Override
    public boolean upgradeEncoding(String encodedPassword) {
        if (isNonHashOrSentinel(encodedPassword)) {
            return false;
        }
        if (encodedPassword.startsWith(ARGON2ID_PREFIX)) {
            try {
                return argon2Encoder.upgradeEncoding(encodedPassword);
            } catch (IllegalArgumentException ex) {
                return false;
            }
        }
        return isValidBcryptFormat(encodedPassword);
    }

    public boolean isSupportedHash(String encodedPassword) {
        if (isNonHashOrSentinel(encodedPassword)) {
            return false;
        }
        return encodedPassword.startsWith(ARGON2ID_PREFIX) || isValidBcryptFormat(encodedPassword);
    }

    private static boolean isNonHashOrSentinel(String encodedPassword) {
        return encodedPassword == null
                || encodedPassword.isBlank()
                || SENTINEL_MARKERS.contains(encodedPassword)
                || encodedPassword.startsWith("!");
    }

    private static boolean isValidBcryptFormat(String encodedPassword) {
        boolean hasAllowedPrefix = encodedPassword.startsWith(BCRYPT_2A_PREFIX)
                || encodedPassword.startsWith(BCRYPT_2B_PREFIX)
                || encodedPassword.startsWith(BCRYPT_2Y_PREFIX);
        return hasAllowedPrefix && BCRYPT_HASH_PATTERN.matcher(encodedPassword).matches();
    }
}
