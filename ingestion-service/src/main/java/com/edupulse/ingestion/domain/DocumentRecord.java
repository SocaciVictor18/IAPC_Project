package com.edupulse.ingestion.domain;

import java.time.Instant;
import java.util.UUID;

/** A row of {@code ingestion.document}. */
public record DocumentRecord(
        UUID id,
        UUID organizationId,
        UUID orgUnitId,
        String title,
        String originalFilename,
        String mimeType,
        long sizeBytes,
        String sha256,
        DocumentCategory category,
        String docType,
        int clearanceLevel,
        int version,
        DocumentStatus status,
        int progressPct,
        String errorMessage,
        String storagePath,
        Integer pageCount,
        Integer parentCount,
        Integer chunkCount,
        UUID uploadedBy,
        Instant uploadedAt,
        Instant indexedAt) {
}
