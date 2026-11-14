package com.studentprep.security;

import com.studentprep.student.User;
import com.studentprep.student.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

@Component
@Order(10)
public class AdminBootstrapRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrapRunner.class);
    private static final String ADMIN_IDENTIFIER = "ADMIN-001";
    private static final String ROLE_ADMIN = "ROLE_ADMIN";
    private static final String GENERATED_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789!@#$%";
    private static final int GENERATED_SECRET_LENGTH = 16;
    private static final Pattern BCRYPT_HASH_PATTERN =
            Pattern.compile("\\A\\$2[aby]\\$\\d{2}\\$[./0-9A-Za-z]{53}\\z");

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final Environment environment;
    private final String adminBootstrapSecret;
    private final SecureRandom secureRandom = new SecureRandom();

    public AdminBootstrapRunner(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            Environment environment,
            @Value("${security.bootstrap.admin-secret:}") String adminBootstrapSecret) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.environment = environment;
        this.adminBootstrapSecret = adminBootstrapSecret;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        boolean isProd = environment.acceptsProfiles(Profiles.of("prod"));
        Optional<User> existingOpt = userRepository.findByIdentifierIgnoreCase(ADMIN_IDENTIFIER);
        boolean hasConfiguredSecret = adminBootstrapSecret != null && !adminBootstrapSecret.isBlank();

        if (existingOpt.isPresent()) {
            User admin = existingOpt.get();
            boolean hasValidHash = isValidStoredHash(admin.getPinHash());

            if (hasConfiguredSecret) {
                String trimmedSecret = adminBootstrapSecret.trim();
                if (hasValidHash && isProd) {
                    // Never overwrite an already bootstrapped or rotated production admin credential.
                    return;
                }
                if (hasValidHash && passwordEncoder.matches(trimmedSecret, admin.getPinHash())) {
                    return;
                }
                admin.setIdentifier(ADMIN_IDENTIFIER);
                admin.setRole(ROLE_ADMIN);
                admin.setPinHash(passwordEncoder.encode(trimmedSecret));
                admin.setPinResetRequired(false);
                userRepository.save(admin);
                log.info("Bootstrapped {} credential using configured environment secret", ADMIN_IDENTIFIER);
                return;
            }

            if (hasValidHash) {
                return;
            }

            if (isProd) {
                throw new IllegalStateException(
                        "ADMIN_BOOTSTRAP_SECRET must be configured in prod when ADMIN-001 requires bootstrap");
            }

            String generated = generateTemporaryDevSecret();
            admin.setIdentifier(ADMIN_IDENTIFIER);
            admin.setRole(ROLE_ADMIN);
            admin.setPinHash(passwordEncoder.encode(generated));
            admin.setPinResetRequired(false);
            userRepository.save(admin);
            log.info(
                    "No ADMIN_BOOTSTRAP_SECRET set in non-prod profile; generated temporary one-time {} credential: {}",
                    ADMIN_IDENTIFIER,
                    generated);
            return;
        }

        if (isProd && !hasConfiguredSecret) {
            throw new IllegalStateException(
                    "ADMIN_BOOTSTRAP_SECRET must be configured in prod when ADMIN-001 does not exist");
        }

        String rawSecret;
        boolean generatedTemporary = false;
        if (hasConfiguredSecret) {
            rawSecret = adminBootstrapSecret.trim();
        } else {
            rawSecret = generateTemporaryDevSecret();
            generatedTemporary = true;
        }

        User admin = new User();
        admin.setId(UUID.randomUUID());
        admin.setIdentifier(ADMIN_IDENTIFIER);
        admin.setPinHash(passwordEncoder.encode(rawSecret));
        admin.setRole(ROLE_ADMIN);
        admin.setPinResetRequired(false);
        userRepository.save(admin);

        if (generatedTemporary) {
            log.info(
                    "Created {} with generated non-prod temporary credential (set ADMIN_BOOTSTRAP_SECRET to configure): {}",
                    ADMIN_IDENTIFIER,
                    rawSecret);
        } else {
            log.info("Created {} with configured bootstrap secret", ADMIN_IDENTIFIER);
        }
    }

    private boolean isValidStoredHash(String hash) {
        if (hash == null || hash.isBlank() || hash.startsWith("!")) {
            return false;
        }
        return hash.startsWith("$argon2id$") || BCRYPT_HASH_PATTERN.matcher(hash).matches();
    }

    private String generateTemporaryDevSecret() {
        StringBuilder builder = new StringBuilder(GENERATED_SECRET_LENGTH);
        for (int i = 0; i < GENERATED_SECRET_LENGTH; i++) {
            int idx = secureRandom.nextInt(GENERATED_ALPHABET.length());
            builder.append(GENERATED_ALPHABET.charAt(idx));
        }
        return builder.toString();
    }
}
