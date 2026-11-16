package com.edupulse.common.crypto;

import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.CipherOutputStream;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Application-level AES-256-GCM encryption (ADR-008, layer 2 and 3).
 * <p>
 * Wire format: {@code [version:1][iv:12][ciphertext + tag:16]}. A fresh random IV is used for
 * every message, so encrypting the same plaintext twice yields different bytes.
 * Used for: personal data of users, parent chunk text (hydrated, never searched), and the
 * original uploaded files on disk.
 */
public class AesGcmCipher {

    private static final byte VERSION = 1;
    private static final int IV_LENGTH = 12;
    private static final int TAG_BITS = 128;
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";

    private final SecretKey key;
    private final SecureRandom random = new SecureRandom();

    public AesGcmCipher(byte[] rawKey) {
        if (rawKey == null || rawKey.length != 32) {
            throw new IllegalArgumentException("AES-256 requires a 32-byte key (got "
                    + (rawKey == null ? 0 : rawKey.length) + " bytes)");
        }
        this.key = new SecretKeySpec(rawKey, "AES");
    }

    /** @param base64Key 32 random bytes, Base64 encoded (e.g. {@code openssl rand -base64 32}) */
    public static AesGcmCipher fromBase64(String base64Key) {
        return new AesGcmCipher(Base64.getDecoder().decode(base64Key.trim()));
    }

    public byte[] encrypt(byte[] plaintext) {
        try {
            byte[] iv = newIv();
            Cipher cipher = cipher(Cipher.ENCRYPT_MODE, iv);
            byte[] ct = cipher.doFinal(plaintext);
            return ByteBuffer.allocate(1 + IV_LENGTH + ct.length).put(VERSION).put(iv).put(ct).array();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Encryption failed", e);
        }
    }

    public byte[] decrypt(byte[] payload) {
        if (payload == null || payload.length < 1 + IV_LENGTH + TAG_BITS / 8) {
            throw new IllegalArgumentException("Ciphertext too short");
        }
        if (payload[0] != VERSION) {
            throw new IllegalArgumentException("Unsupported ciphertext version " + payload[0]);
        }
        try {
            byte[] iv = new byte[IV_LENGTH];
            System.arraycopy(payload, 1, iv, 0, IV_LENGTH);
            Cipher cipher = cipher(Cipher.DECRYPT_MODE, iv);
            return cipher.doFinal(payload, 1 + IV_LENGTH, payload.length - 1 - IV_LENGTH);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Decryption failed (wrong key or tampered data)", e);
        }
    }

    public byte[] encryptString(String plaintext) {
        return plaintext == null ? null : encrypt(plaintext.getBytes(StandardCharsets.UTF_8));
    }

    public String decryptString(byte[] payload) {
        return payload == null ? null : new String(decrypt(payload), StandardCharsets.UTF_8);
    }

    /** Streams {@code in} to {@code out} encrypted. Neither stream is closed by the caller's contract: out is closed here. */
    public void encrypt(InputStream in, OutputStream out) throws IOException {
        try {
            byte[] iv = newIv();
            out.write(VERSION);
            out.write(iv);
            try (CipherOutputStream cos = new CipherOutputStream(out, cipher(Cipher.ENCRYPT_MODE, iv))) {
                in.transferTo(cos);
            }
        } catch (GeneralSecurityException e) {
            throw new IOException("Encryption failed", e);
        }
    }

    /** Returns a stream that decrypts {@code in}. GCM authenticates the whole file on close/EOF. */
    public InputStream decryptingStream(InputStream in) throws IOException {
        int version = in.read();
        if (version != VERSION) {
            throw new IOException("Unsupported or corrupt encrypted file (version " + version + ")");
        }
        byte[] iv = in.readNBytes(IV_LENGTH);
        try {
            return new CipherInputStream(in, cipher(Cipher.DECRYPT_MODE, iv));
        } catch (GeneralSecurityException e) {
            throw new IOException("Decryption failed", e);
        }
    }

    private byte[] newIv() {
        byte[] iv = new byte[IV_LENGTH];
        random.nextBytes(iv);
        return iv;
    }

    private Cipher cipher(int mode, byte[] iv) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(mode, key, new GCMParameterSpec(TAG_BITS, iv));
        return cipher;
    }
}
