package com.edupulse.ingestion.service;

import com.edupulse.common.crypto.AesGcmCipher;
import com.edupulse.ingestion.config.IngestionProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * Stores original uploads outside the web root, encrypted with AES-256-GCM (ADR-008 layer 3).
 * Layout: {@code {root}/{organizationId}/{documentId}.bin}. Original file names never reach the
 * file system, so path traversal through a crafted name is impossible.
 */
@Service
@RequiredArgsConstructor
public class StorageService {

    private final IngestionProperties props;
    private final AesGcmCipher cipher;

    public String store(UUID organizationId, UUID documentId, InputStream plaintext) throws IOException {
        Path dir = root().resolve(organizationId.toString());
        Files.createDirectories(dir);
        Path target = dir.resolve(documentId + ".bin");
        try (OutputStream out = Files.newOutputStream(target)) {
            cipher.encrypt(plaintext, out);
        }
        return root().relativize(target).toString().replace('\\', '/');
    }

    /** Decrypts the stored file into a temporary plaintext file the parsers can read; caller deletes it. */
    public Path decryptToTemp(String storagePath, String suffix) throws IOException {
        Path source = root().resolve(storagePath).normalize();
        if (!source.startsWith(root())) {
            throw new IOException("Invalid storage path");
        }
        Path tmp = Files.createTempFile("edupulse-", suffix);
        try (InputStream in = cipher.decryptingStream(Files.newInputStream(source))) {
            Files.copy(in, tmp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        return tmp;
    }

    public void delete(String storagePath) throws IOException {
        Path p = root().resolve(storagePath).normalize();
        if (p.startsWith(root())) {
            Files.deleteIfExists(p);
        }
    }

    private Path root() {
        return Path.of(props.storageRoot()).toAbsolutePath().normalize();
    }
}
