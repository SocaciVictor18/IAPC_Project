package com.edupulse.common.crypto;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AesGcmCipherTest {

    private static byte[] key() {
        byte[] k = new byte[32];
        new SecureRandom().nextBytes(k);
        return k;
    }

    @Test
    void roundTripsStrings() {
        AesGcmCipher cipher = new AesGcmCipher(key());
        byte[] ct = cipher.encryptString("Jane Doe");
        assertThat(new String(ct, StandardCharsets.ISO_8859_1)).doesNotContain("Jane");
        assertThat(cipher.decryptString(ct)).isEqualTo("Jane Doe");
    }

    @Test
    void sameInputGivesDifferentCiphertext() {
        AesGcmCipher cipher = new AesGcmCipher(key());
        assertThat(cipher.encryptString("x")).isNotEqualTo(cipher.encryptString("x"));
    }

    @Test
    void detectsTampering() {
        AesGcmCipher cipher = new AesGcmCipher(key());
        byte[] ct = cipher.encryptString("restricted");
        ct[ct.length - 1] ^= 0x01;
        assertThatThrownBy(() -> cipher.decrypt(ct)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void wrongKeyFails() {
        byte[] ct = new AesGcmCipher(key()).encryptString("secret");
        assertThatThrownBy(() -> new AesGcmCipher(key()).decrypt(ct)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsShortKeys() {
        assertThatThrownBy(() -> new AesGcmCipher(new byte[16])).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void streamsFiles() throws Exception {
        AesGcmCipher cipher = new AesGcmCipher(key());
        byte[] data = new byte[300_000];
        new SecureRandom().nextBytes(data);
        ByteArrayOutputStream enc = new ByteArrayOutputStream();
        cipher.encrypt(new ByteArrayInputStream(data), enc);
        byte[] decrypted = cipher.decryptingStream(new ByteArrayInputStream(enc.toByteArray())).readAllBytes();
        assertThat(decrypted).isEqualTo(data);
    }
}
