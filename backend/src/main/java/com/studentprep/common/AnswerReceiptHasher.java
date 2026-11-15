package com.studentprep.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Computes a deterministic SHA-256 cryptographic receipt over a candidate's examination session ID
 * and canonical sorted question answers for tamper-evident non-repudiation.
 */
public final class AnswerReceiptHasher {

    private AnswerReceiptHasher() {
    }

    /**
     * Computes the full 64-character lowercase SHA-256 hex digest over {@code sessionId} and {@code answers}.
     */
    public static String computeSha256(UUID sessionId, Map<String, String> answers) {
        StringBuilder canonical = new StringBuilder();
        canonical.append("session=")
                .append(sessionId != null ? sessionId.toString().toLowerCase(Locale.ROOT) : "none");

        if (answers != null && !answers.isEmpty()) {
            Map<String, String> sorted = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            for (Map.Entry<String, String> entry : answers.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    String key = entry.getKey().trim();
                    String val = entry.getValue().trim().toUpperCase(Locale.ROOT);
                    if (!key.isEmpty() && !val.isEmpty()) {
                        sorted.put(key, val);
                    }
                }
            }
            for (Map.Entry<String, String> entry : sorted.entrySet()) {
                canonical.append('|')
                        .append(entry.getKey().toLowerCase(Locale.ROOT))
                        .append('=')
                        .append(entry.getValue());
            }
        }

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 algorithm not available", ex);
        }
    }

    /**
     * Formats the first 12 characters of a SHA-256 hex string as a human-readable receipt code {@code XXXX-XXXX-XXXX}.
     */
    public static String formatReceiptCode(String sha256Hex) {
        if (sha256Hex == null || sha256Hex.length() < 12) {
            return "N/A";
        }
        String upper = sha256Hex.substring(0, 12).toUpperCase(Locale.ROOT);
        return upper.substring(0, 4) + "-" + upper.substring(4, 8) + "-" + upper.substring(8, 12);
    }

    /**
     * Convenience method returning the 12-character formatted receipt code {@code XXXX-XXXX-XXXX}.
     */
    public static String computeReceiptCode(UUID sessionId, Map<String, String> answers) {
        return formatReceiptCode(computeSha256(sessionId, answers));
    }
}
