package com.studentprep.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Production startup guard that fails fast before the web server accepts traffic if any
 * security-critical secret, cookie flag, CORS allow-list, or bootstrap configuration is missing,
 * weak, or left on development/placeholder defaults.
 *
 * <p>Implements {@link SmartInitializingSingleton} so validation runs after Flyway migrations
 * finish in {@code InitializingBean} phase and before embedded servlet containers start listening.
 * Uses {@link JdbcTemplate} rather than module JPA repositories to preserve acyclic Spring Modulith
 * package boundaries.
 */
@Component
@Profile("prod")
public class ProductionSecurityValidator implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(ProductionSecurityValidator.class);

    private static final int MIN_JWT_SECRET_BYTES = 32;
    private static final int MIN_JWT_DISTINCT_CHARS = 6;
    private static final int MIN_ADMIN_SECRET_CHARS = 12;
    private static final int MIN_ADMIN_DISTINCT_CHARS = 5;

    private static final String ADMIN_PIN_HASH_QUERY =
            "SELECT pin_hash FROM users WHERE UPPER(TRIM(identifier)) = 'ADMIN-001'";

    private static final Pattern ARGON2ID_HASH_PATTERN =
            Pattern.compile("\\A\\$argon2id\\$v=\\d+\\$m=\\d+,t=\\d+,p=\\d+\\$[A-Za-z0-9+/=_-]+\\$[A-Za-z0-9+/=_-]+\\z");
    private static final Pattern BCRYPT_HASH_PATTERN =
            Pattern.compile("\\A\\$2[aby]\\$\\d{2}\\$[./0-9A-Za-z]{53}\\z");

    private static final Set<String> KNOWN_DEV_JWT_SECRETS = Set.of(
            "super_secret_jwt_key_for_local_development_only_do_not_use_in_prod",
            "test_only_jwt_secret_key_at_least_32_bytes_long_12345",
            "404e635266556a586e3272357538782f413f4428472b4b6250645367566b5970",
            "secret",
            "changeme",
            "change_me",
            "placeholder",
            "default"
    );

    private static final Set<String> WEAK_ADMIN_SECRETS = Set.of(
            "admin123",
            "admin",
            "12345",
            "123456",
            "123456789012",
            "password",
            "password123",
            "password1234",
            "secret",
            "root",
            "studentprep",
            "admin_bootstrap_secret",
            "test_admin_bootstrap_secret_123",
            "!admin_bootstrap_required!",
            "!legacy_invalidated!",
            "!locked!"
    );

    private static final Set<String> PLACEHOLDER_DOCLING_KEYS = Set.of(
            "your_docling_api_key_here",
            "test_docling_api_key",
            "docling_api_key",
            "docling-api-key",
            "changeme",
            "change_me",
            "placeholder",
            "replace_me",
            "default",
            "secret",
            "password",
            "none",
            "null",
            "12345",
            "admin123"
    );

    private static final Set<String> DISALLOWED_S3_CREDENTIALS = Set.of(
            "minioadmin",
            "changeme",
            "change_me",
            "placeholder",
            "replace_me",
            "default",
            "secret",
            "password",
            "none",
            "null"
    );

    private final Environment environment;
    private final ObjectProvider<JdbcTemplate> jdbcTemplateProvider;

    public ProductionSecurityValidator(
            Environment environment,
            ObjectProvider<JdbcTemplate> jdbcTemplateProvider) {
        this.environment = Objects.requireNonNull(environment, "environment must not be null");
        this.jdbcTemplateProvider = Objects.requireNonNull(jdbcTemplateProvider, "jdbcTemplateProvider must not be null");
    }

    @Override
    public void afterSingletonsInstantiated() {
        validate();
    }

    /**
     * Validates all required production security properties and fails fast with an
     * {@link IllegalStateException} listing violated properties without ever logging secret values.
     */
    public void validate() {
        List<String> violations = new ArrayList<>();

        validateJwtSecret(violations);
        validateAdminBootstrapSecret(violations);
        validateCorsAllowedOrigins(violations);
        validateCookieSecure(violations);
        validateDoclingApiKey(violations);
        validateS3Credentials(violations);

        if (!violations.isEmpty()) {
            String summary = String.join("; ", violations);
            log.error("Production security validation failed with {} violation(s): {}", violations.size(), summary);
            throw new IllegalStateException("Production security validation failed: " + summary);
        }

        log.info("Production security validation passed.");
    }

    private void validateJwtSecret(List<String> violations) {
        String raw = resolveProperty("security.jwt.secret", violations);
        if (raw == null || raw.isBlank()) {
            violations.add("security.jwt.secret (JWT_SECRET) must not be missing or blank in prod");
            return;
        }

        String trimmed = raw.trim();
        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (trimmed.getBytes(StandardCharsets.UTF_8).length < MIN_JWT_SECRET_BYTES) {
            violations.add("security.jwt.secret (JWT_SECRET) must be at least 32 UTF-8 bytes in prod");
            return;
        }
        if (KNOWN_DEV_JWT_SECRETS.contains(lower) || hasPlaceholderPattern(lower)) {
            violations.add("security.jwt.secret (JWT_SECRET) must not use a dev, test, or placeholder value in prod");
            return;
        }
        if (trimmed.chars().distinct().count() < MIN_JWT_DISTINCT_CHARS) {
            violations.add("security.jwt.secret (JWT_SECRET) has insufficient character entropy for prod");
        }
    }

    private void validateAdminBootstrapSecret(List<String> violations) {
        String raw = resolveProperty("security.bootstrap.admin-secret", violations);
        if (raw != null && !raw.isBlank()) {
            String trimmed = raw.trim();
            String lower = trimmed.toLowerCase(Locale.ROOT);
            if (trimmed.length() < MIN_ADMIN_SECRET_CHARS) {
                violations.add("security.bootstrap.admin-secret (ADMIN_BOOTSTRAP_SECRET) must be at least 12 characters in prod");
                return;
            }
            if (trimmed.startsWith("!")
                    || WEAK_ADMIN_SECRETS.contains(lower)
                    || hasPlaceholderPattern(lower)
                    || trimmed.chars().distinct().count() < MIN_ADMIN_DISTINCT_CHARS) {
                violations.add("security.bootstrap.admin-secret (ADMIN_BOOTSTRAP_SECRET) must not be weak or a placeholder in prod");
            }
            return;
        }

        JdbcTemplate jdbcTemplate = jdbcTemplateProvider.getIfAvailable();
        if (jdbcTemplate == null) {
            violations.add("security.bootstrap.admin-secret (ADMIN_BOOTSTRAP_SECRET) is blank and ADMIN-001 bootstrap state cannot be verified without JdbcTemplate");
            return;
        }

        try {
            List<String> adminHashes = jdbcTemplate.queryForList(ADMIN_PIN_HASH_QUERY, String.class);
            if (adminHashes.isEmpty()) {
                violations.add("security.bootstrap.admin-secret (ADMIN_BOOTSTRAP_SECRET) is blank while ADMIN-001 does not exist");
                return;
            }
            for (String hash : adminHashes) {
                if (!isSupportedPasswordHash(hash)) {
                    violations.add("security.bootstrap.admin-secret (ADMIN_BOOTSTRAP_SECRET) is blank while ADMIN-001 requires bootstrap");
                    return;
                }
            }
        } catch (DataAccessException ex) {
            violations.add("security.bootstrap.admin-secret (ADMIN_BOOTSTRAP_SECRET) is blank and ADMIN-001 bootstrap state could not be queried");
        }
    }

    private void validateCorsAllowedOrigins(List<String> violations) {
        String raw = resolveProperty("cors.allowed-origins", violations);
        if (raw == null || raw.isBlank()) {
            violations.add("cors.allowed-origins (CORS_ALLOWED_ORIGINS) must not be blank in prod");
            return;
        }
        if (raw.contains("*")) {
            violations.add("cors.allowed-origins (CORS_ALLOWED_ORIGINS) must not contain wildcard '*' in prod");
            return;
        }

        String[] parts = raw.split(",", -1);
        for (String part : parts) {
            String origin = part.trim();
            if (origin.isEmpty() || !isValidOrigin(origin)) {
                violations.add("cors.allowed-origins (CORS_ALLOWED_ORIGINS) contains an invalid origin entry");
                return;
            }
        }
    }

    private void validateCookieSecure(List<String> violations) {
        String raw = resolveProperty("security.cookie.secure", violations);
        if (raw == null || !"true".equalsIgnoreCase(raw.trim())) {
            violations.add("security.cookie.secure (COOKIE_SECURE / APP_COOKIE_SECURE) must be true in prod");
        }
    }

    private void validateDoclingApiKey(List<String> violations) {
        String raw = resolveProperty("docling.api-key", violations);
        if (raw == null || raw.isBlank()) {
            violations.add("docling.api-key (DOCLING_API_KEY) must not be blank in prod");
            return;
        }

        String lower = raw.trim().toLowerCase(Locale.ROOT);
        if (PLACEHOLDER_DOCLING_KEYS.contains(lower) || hasPlaceholderPattern(lower)) {
            violations.add("docling.api-key (DOCLING_API_KEY) must not use a placeholder or test default value in prod");
        }
    }

    private void validateS3Credentials(List<String> violations) {
        String accessKey = resolveFirstNonBlankProperty("s3.accessKey", "s3.access-key", violations);
        if (isInvalidS3Credential(accessKey)) {
            violations.add("s3.accessKey (S3_ACCESS_KEY) must not be blank, placeholder, or 'minioadmin' in prod");
        }

        String secretKey = resolveFirstNonBlankProperty("s3.secretKey", "s3.secret-key", violations);
        if (isInvalidS3Credential(secretKey)) {
            violations.add("s3.secretKey (S3_SECRET_KEY) must not be blank, placeholder, or 'minioadmin' in prod");
        }
    }

    private String resolveFirstNonBlankProperty(String primaryKey, String fallbackKey, List<String> violations) {
        String primary = resolveProperty(primaryKey, violations);
        if (primary != null && !primary.isBlank()) {
            return primary;
        }
        return resolveProperty(fallbackKey, violations);
    }

    private String resolveProperty(String propertyName, List<String> violations) {
        try {
            return environment.getProperty(propertyName);
        } catch (IllegalArgumentException ex) {
            violations.add(propertyName + " could not resolve configuration placeholder");
            return null;
        }
    }

    private static boolean isInvalidS3Credential(String value) {
        if (value == null || value.isBlank()) {
            return true;
        }
        String lower = value.trim().toLowerCase(Locale.ROOT);
        return DISALLOWED_S3_CREDENTIALS.contains(lower)
                || hasPlaceholderPattern(lower)
                || lower.startsWith("test_");
    }

    private static boolean hasPlaceholderPattern(String lowerTrimmed) {
        return lowerTrimmed.startsWith("your_")
                || lowerTrimmed.startsWith("your-")
                || lowerTrimmed.startsWith("placeholder")
                || lowerTrimmed.startsWith("changeme")
                || lowerTrimmed.startsWith("change_me")
                || lowerTrimmed.startsWith("replace_")
                || lowerTrimmed.startsWith("replace-")
                || lowerTrimmed.startsWith("example_")
                || lowerTrimmed.startsWith("example-")
                || (lowerTrimmed.startsWith("<") && lowerTrimmed.endsWith(">"))
                || lowerTrimmed.contains("do_not_use_in_prod")
                || lowerTrimmed.contains("for_local_development")
                || lowerTrimmed.contains("changeme")
                || lowerTrimmed.contains("change_me")
                || lowerTrimmed.contains("placeholder")
                || lowerTrimmed.contains("replace_me");
    }

    private static boolean isValidOrigin(String origin) {
        try {
            URI uri = URI.create(origin);
            String scheme = uri.getScheme();
            if (scheme == null) {
                return false;
            }
            boolean httpScheme = "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
            String host = uri.getHost();
            int port = uri.getPort();
            boolean validPort = port == -1 || (port >= 1 && port <= 65535);
            String path = uri.getRawPath();
            return httpScheme
                    && host != null
                    && !host.isBlank()
                    && validPort
                    && uri.getRawUserInfo() == null
                    && (path == null || path.isEmpty())
                    && uri.getRawQuery() == null
                    && uri.getRawFragment() == null;
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    private static boolean isSupportedPasswordHash(String hash) {
        if (hash == null || hash.isBlank() || hash.startsWith("!")) {
            return false;
        }
        return ARGON2ID_HASH_PATTERN.matcher(hash).matches()
                || BCRYPT_HASH_PATTERN.matcher(hash).matches();
    }
}
