package com.studentprep.ingestion;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientResponseException;

import java.util.regex.Pattern;

/**
 * Redacts upstream API keys, authorization headers, query credentials, and raw HTTP error bodies
 * before persisting ingestion errors or emitting logs.
 */
@Component
public class ErrorSanitizer {

    private static final String REDACTED = "[REDACTED]";
    private static final String DEFAULT_ERROR_MESSAGE = "Upstream ingestion processing failed";
    private static final int MAX_MESSAGE_LENGTH = 300;
    private static final int MIN_EXPLICIT_SECRET_LENGTH = 4;

    private static final Pattern QUERY_SECRET_PATTERN = Pattern.compile(
            "([?&](?:key|api_key|apikey|access_token|token|secret|password|auth)=)[^&\\s\"'<>]+",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern HEADER_SECRET_PATTERN = Pattern.compile(
            "((?:x-goog-api-key|x-api-key|x-docling-api-key|authorization|api[-_]?key|secret[-_]?key|access[-_]?token)\\s*[:=]\\s*[\"']?)[^\\s\"',;}\\]]+",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern GENERIC_KEY_ASSIGNMENT_PATTERN = Pattern.compile(
            "\\b(key\\s*[:=]\\s*[\"']?)[^\\s\"',;}\\]]+",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern AUTH_SCHEME_TOKEN_PATTERN = Pattern.compile(
            "((?:Bearer|Basic)\\s+)[A-Za-z0-9._\\-+/=]+",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern GOOGLE_API_KEY_PATTERN = Pattern.compile("AIza[0-9A-Za-z\\-_]{16,}");
    private static final Pattern JSON_ERROR_BODY_PATTERN = Pattern.compile(
            "(?:\\bbody\\s+)?\\{\\s*\"(?:error|detail|message|status)\"\\s*:.*$",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern HTML_BODY_PATTERN = Pattern.compile(
            "(?:<!DOCTYPE\\s+html|<html\\b).*$",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern STACK_FRAME_PATTERN = Pattern.compile(
            "\\bat\\s+[\\w.$]+\\([^)]*(?::\\d+|Unknown Source|Native Method)\\)"
    );

    private final String geminiApiKey;
    private final String doclingApiKey;
    private final String s3SecretKey;

    public ErrorSanitizer(
            @Value("${gemini.api-key:}") String geminiApiKey,
            @Value("${docling.api-key:}") String doclingApiKey,
            @Value("${s3.secretKey:}") String s3SecretKey) {
        this.geminiApiKey = geminiApiKey;
        this.doclingApiKey = doclingApiKey;
        this.s3SecretKey = s3SecretKey;
    }

    /**
     * Sanitizes a throwable using both configured runtime credentials and any extra secrets provided.
     */
    public String sanitizeException(Throwable throwable, String... extraSecrets) {
        String[] combined = combineSecrets(extraSecrets);
        return sanitizeThrowable(throwable, combined);
    }

    /**
     * Sanitizes a raw message using configured runtime credentials and any extra secrets provided.
     */
    public String sanitizeMessage(String rawMessage, String... extraSecrets) {
        String[] combined = combineSecrets(extraSecrets);
        return sanitize(rawMessage, combined);
    }

    /**
     * Redacts upstream HTTP bodies, API keys, tokens, and stack frames from an exception.
     */
    public static String sanitizeThrowable(Throwable throwable, String... extraSecrets) {
        if (throwable == null) {
            return DEFAULT_ERROR_MESSAGE;
        }
        RestClientResponseException upstreamHttp = findRestClientResponseException(throwable);
        if (upstreamHttp != null) {
            return "Upstream service returned HTTP " + upstreamHttp.getStatusCode().value();
        }
        return sanitize(throwable.getMessage(), extraSecrets);
    }

    /**
     * Redacts API keys, query/header secrets, bearer tokens, and raw upstream response payloads.
     */
    public static String sanitize(String rawMessage, String... extraSecrets) {
        if (rawMessage == null || rawMessage.isBlank()) {
            return DEFAULT_ERROR_MESSAGE;
        }

        String sanitized = rawMessage.replace('\r', ' ').replace('\n', ' ').trim();

        if (extraSecrets != null) {
            for (String secret : extraSecrets) {
                if (secret != null && secret.length() >= MIN_EXPLICIT_SECRET_LENGTH) {
                    sanitized = sanitized.replace(secret, REDACTED);
                }
            }
        }

        sanitized = QUERY_SECRET_PATTERN.matcher(sanitized).replaceAll("$1" + REDACTED);
        sanitized = AUTH_SCHEME_TOKEN_PATTERN.matcher(sanitized).replaceAll("$1" + REDACTED);
        sanitized = HEADER_SECRET_PATTERN.matcher(sanitized).replaceAll("$1" + REDACTED);
        sanitized = GENERIC_KEY_ASSIGNMENT_PATTERN.matcher(sanitized).replaceAll("$1" + REDACTED);
        sanitized = GOOGLE_API_KEY_PATTERN.matcher(sanitized).replaceAll(REDACTED);
        sanitized = JSON_ERROR_BODY_PATTERN.matcher(sanitized).replaceAll("[upstream response redacted]");
        sanitized = HTML_BODY_PATTERN.matcher(sanitized).replaceAll("[upstream response redacted]");
        sanitized = STACK_FRAME_PATTERN.matcher(sanitized).replaceAll("").trim();

        if (sanitized.isBlank()) {
            return DEFAULT_ERROR_MESSAGE;
        }
        if (sanitized.length() > MAX_MESSAGE_LENGTH) {
            return sanitized.substring(0, MAX_MESSAGE_LENGTH) + "...";
        }
        return sanitized;
    }

    private String[] combineSecrets(String... extraSecrets) {
        int extraCount = extraSecrets != null ? extraSecrets.length : 0;
        String[] combined = new String[3 + extraCount];
        combined[0] = geminiApiKey;
        combined[1] = doclingApiKey;
        combined[2] = s3SecretKey;
        if (extraCount > 0) {
            System.arraycopy(extraSecrets, 0, combined, 3, extraCount);
        }
        return combined;
    }

    private static RestClientResponseException findRestClientResponseException(Throwable throwable) {
        Throwable current = throwable;
        int depth = 0;
        while (current != null && depth < 10) {
            if (current instanceof RestClientResponseException responseException) {
                return responseException;
            }
            current = current.getCause();
            depth++;
        }
        return null;
    }
}
