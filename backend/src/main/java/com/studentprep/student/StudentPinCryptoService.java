package com.studentprep.student;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Authenticated AES-256-GCM vault for encrypting and decrypting recoverable student login PINs
 * stored in {@code students.pin_ciphertext} for ROLE_ADMIN credential PDF export.
 */
@Service
public class StudentPinCryptoService {

    static final String DECRYPTION_ERROR_MESSAGE =
            "Unable to decrypt stored student PIN. Check CREDENTIAL_ENCRYPTION_KEY or reset affected student PINs.";

    private static final String KEY_DIGEST_ALGORITHM = "SHA-256";
    private static final String KEY_ALGORITHM = "AES";
    private static final String CIPHER_TRANSFORMATION = "AES/GCM/NoPadding";
    private static final String ENVELOPE_VERSION = "v1";
    private static final String ENVELOPE_DELIMITER = ":";
    private static final int GCM_IV_LENGTH_BYTES = 12;
    private static final int GCM_TAG_LENGTH_BITS = 128;
    private static final int GCM_TAG_LENGTH_BYTES = GCM_TAG_LENGTH_BITS / Byte.SIZE;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final SecretKeySpec secretKeySpec;

    public StudentPinCryptoService(
            @Value("${security.credential-vault.key:local_dev_credential_vault_key_change_in_prod}") String secretKey) {
        this.secretKeySpec = deriveAesKey(secretKey);
    }

    /**
     * Encrypts a plaintext student PIN with AES-256-GCM and a fresh 12-byte random IV.
     *
     * @param rawPin plaintext student PIN
     * @return versioned envelope formatted as {@code v1:<base64-iv>:<base64-ct+tag>}
     */
    public String encrypt(String rawPin) {
        if (rawPin == null || rawPin.isBlank()) {
            throw new IllegalArgumentException("Student PIN must not be null or blank");
        }
        byte[] iv = new byte[GCM_IV_LENGTH_BYTES];
        SECURE_RANDOM.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance(CIPHER_TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, secretKeySpec, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
            byte[] cipherTextWithTag = cipher.doFinal(rawPin.getBytes(StandardCharsets.UTF_8));
            Base64.Encoder encoder = Base64.getEncoder();
            return ENVELOPE_VERSION
                    + ENVELOPE_DELIMITER
                    + encoder.encodeToString(iv)
                    + ENVELOPE_DELIMITER
                    + encoder.encodeToString(cipherTextWithTag);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Unable to encrypt student PIN", ex);
        }
    }

    /**
     * Decrypts a stored {@code v1} AES-256-GCM student PIN envelope.
     *
     * @param ciphertextEnvelope versioned ciphertext envelope from {@code students.pin_ciphertext}
     * @return decrypted plaintext student PIN
     * @throws IllegalStateException if the envelope is malformed or fails GCM authentication
     */
    public String decrypt(String ciphertextEnvelope) {
        try {
            ParsedEnvelope envelope = parseEnvelope(ciphertextEnvelope);
            Cipher cipher = Cipher.getInstance(CIPHER_TRANSFORMATION);
            cipher.init(
                    Cipher.DECRYPT_MODE,
                    secretKeySpec,
                    new GCMParameterSpec(GCM_TAG_LENGTH_BITS, envelope.iv()));
            byte[] plaintextBytes = cipher.doFinal(envelope.cipherTextWithTag());
            String rawPin = new String(plaintextBytes, StandardCharsets.UTF_8);
            if (rawPin.isBlank()) {
                throw new IllegalArgumentException("Decrypted student PIN must not be blank");
            }
            return rawPin;
        } catch (IllegalArgumentException | GeneralSecurityException ex) {
            throw new IllegalStateException(DECRYPTION_ERROR_MESSAGE, ex);
        }
    }

    private static SecretKeySpec deriveAesKey(String secretKey) {
        if (secretKey == null || secretKey.isBlank()) {
            throw new IllegalArgumentException("security.credential-vault.key must not be null or blank");
        }
        try {
            MessageDigest digest = MessageDigest.getInstance(KEY_DIGEST_ALGORITHM);
            byte[] keyBytes = digest.digest(secretKey.getBytes(StandardCharsets.UTF_8));
            return new SecretKeySpec(keyBytes, KEY_ALGORITHM);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 algorithm is unavailable", ex);
        }
    }

    private static ParsedEnvelope parseEnvelope(String ciphertextEnvelope) {
        if (ciphertextEnvelope == null || ciphertextEnvelope.isBlank()) {
            throw new IllegalArgumentException("PIN ciphertext envelope must not be blank");
        }
        String[] parts = ciphertextEnvelope.split(ENVELOPE_DELIMITER, -1);
        if (parts.length != 3 || !ENVELOPE_VERSION.equals(parts[0]) || parts[1].isBlank() || parts[2].isBlank()) {
            throw new IllegalArgumentException("Malformed PIN ciphertext envelope");
        }
        Base64.Decoder decoder = Base64.getDecoder();
        byte[] iv = decoder.decode(parts[1]);
        byte[] cipherTextWithTag = decoder.decode(parts[2]);
        if (iv.length != GCM_IV_LENGTH_BYTES || cipherTextWithTag.length < GCM_TAG_LENGTH_BYTES) {
            throw new IllegalArgumentException("Invalid AES-GCM envelope payload lengths");
        }
        return new ParsedEnvelope(iv, cipherTextWithTag);
    }

    private record ParsedEnvelope(byte[] iv, byte[] cipherTextWithTag) {
    }
}
