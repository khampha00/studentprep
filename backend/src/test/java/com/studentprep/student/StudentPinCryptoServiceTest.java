package com.studentprep.student;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.crypto.AEADBadTagException;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StudentPinCryptoServiceTest {

    private static final String PRIMARY_KEY = "test_credential_vault_key_with_sufficient_entropy_01";
    private static final String OTHER_KEY = "different_credential_vault_key_with_sufficient_entropy_02";

    private StudentPinCryptoService cryptoService;

    @BeforeEach
    void setUp() {
        cryptoService = new StudentPinCryptoService(PRIMARY_KEY);
    }

    @Test
    void constructor_RejectsNullOrBlankSecretKey() {
        assertThatThrownBy(() -> new StudentPinCryptoService(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be null or blank");

        assertThatThrownBy(() -> new StudentPinCryptoService("   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be null or blank");
    }

    @Test
    void encryptAndDecrypt_RoundTripsFiveDigitPinsAcrossInstances() {
        StudentPinCryptoService readerService = new StudentPinCryptoService(PRIMARY_KEY);
        for (String pin : new String[]{"00419", "73914", "99999", "10203"}) {
            String envelope = cryptoService.encrypt(pin);
            String[] parts = envelope.split(":", -1);

            assertThat(parts).hasSize(3);
            assertThat(parts[0]).isEqualTo("v1");
            assertThat(Base64.getDecoder().decode(parts[1])).hasSize(12);
            assertThat(Base64.getDecoder().decode(parts[2]).length).isGreaterThanOrEqualTo(16 + pin.length());
            assertThat(envelope).doesNotContain(pin);
            assertThat(cryptoService.decrypt(envelope)).isEqualTo(pin);
            assertThat(readerService.decrypt(envelope)).isEqualTo(pin);
        }
    }

    @Test
    void encrypt_RejectsNullOrBlankPin() {
        assertThatThrownBy(() -> cryptoService.encrypt(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> cryptoService.encrypt("  "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void encrypt_GeneratesUniqueIvsAndCiphertextsPerCall() {
        Set<String> ivs = new HashSet<>();
        Set<String> envelopes = new HashSet<>();

        for (int i = 0; i < 20; i++) {
            String envelope = cryptoService.encrypt("73914");
            String iv = envelope.split(":", -1)[1];
            ivs.add(iv);
            envelopes.add(envelope);
            assertThat(cryptoService.decrypt(envelope)).isEqualTo("73914");
        }

        assertThat(ivs).hasSize(20);
        assertThat(envelopes).hasSize(20);
    }

    @Test
    void decrypt_RejectsNullBlankAndMalformedEnvelopes() {
        String validIv = Base64.getEncoder().encodeToString(new byte[12]);
        String validLengthCt = Base64.getEncoder().encodeToString(new byte[21]);
        String shortIv = Base64.getEncoder().encodeToString(new byte[8]);
        String shortCt = Base64.getEncoder().encodeToString(new byte[8]);

        for (String badEnvelope : new String[]{
                null,
                "",
                "   ",
                "plain-73914",
                "v1:onlytwo",
                "v1:" + validIv + ":" + validLengthCt + ":extra",
                "v2:" + validIv + ":" + validLengthCt,
                "v1::" + validLengthCt,
                "v1:" + validIv + ":",
                "v1:%%%invalid%%%:" + validLengthCt,
                "v1:" + shortIv + ":" + validLengthCt,
                "v1:" + validIv + ":" + shortCt
        }) {
            assertThatThrownBy(() -> cryptoService.decrypt(badEnvelope))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage(StudentPinCryptoService.DECRYPTION_ERROR_MESSAGE);
        }
    }

    @Test
    void decrypt_ThrowsIllegalStateExceptionOnWrongKeyOrTamperedPayload() {
        String envelope = cryptoService.encrypt("73914");
        StudentPinCryptoService wrongKeyService = new StudentPinCryptoService(OTHER_KEY);

        assertThatThrownBy(() -> wrongKeyService.decrypt(envelope))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(StudentPinCryptoService.DECRYPTION_ERROR_MESSAGE)
                .hasCauseInstanceOf(AEADBadTagException.class);

        String[] parts = envelope.split(":", -1);
        byte[] tamperedCt = Base64.getDecoder().decode(parts[2]);
        tamperedCt[0] ^= 0x01;
        String tamperedEnvelope = parts[0] + ":" + parts[1] + ":" + Base64.getEncoder().encodeToString(tamperedCt);

        assertThatThrownBy(() -> cryptoService.decrypt(tamperedEnvelope))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(StudentPinCryptoService.DECRYPTION_ERROR_MESSAGE)
                .hasCauseInstanceOf(AEADBadTagException.class);
    }
}
