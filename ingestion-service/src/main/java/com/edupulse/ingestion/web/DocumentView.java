package com.edupulse.ingestion.web;

import com.edupulse.ingestion.domain.DocumentCategory;
import com.edupulse.ingestion.domain.DocumentRecord;
import com.edupulse.ingestion.domain.DocumentStatus;

import java.time.Instant;
import java.util.UUID;

/** Public representation of a document (never exposes the storage path). */
public record DocumentView(
        UUID id,
        UUID organizationId,
        UUID orgUnitId,
        String title,
        String filename,
        String mimeType,
        long sizeBytes,
        DocumentCategory category,
        String docType,
        int clearanceLevel,
        int version,
        DocumentStatus status,
        int progressPct,
        String error,
        Integer pageCount,
        Integer parentCount,
        Integer chunkCount,
        UUID uploadedBy,
        Instant uploadedAt,
        Instant indexedAt) {

    public static DocumentView of(DocumentRecord d) {
        return new DocumentView(d.id(), d.organizationId(), d.orgUnitId(), d.title(), d.originalFilename(), d.mimeType(),
                d.sizeBytes(), d.category(), d.docType(), d.clearanceLevel(), d.version(), d.status(), d.progressPct(),
                d.errorMessage(), d.pageCount(), d.parentCount(), d.chunkCount(), d.uploadedBy(), d.uploadedAt(),
                d.indexedAt());
    }
}
