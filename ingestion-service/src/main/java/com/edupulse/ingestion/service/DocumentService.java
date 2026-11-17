package com.edupulse.ingestion.service;

import com.edupulse.common.security.TenantContext;
import com.edupulse.common.web.ApiException;
import com.edupulse.ingestion.config.IngestionProperties;
import com.edupulse.ingestion.domain.DocumentCategory;
import com.edupulse.ingestion.domain.DocumentRecord;
import com.edupulse.ingestion.domain.DocumentStatus;
import com.edupulse.ingestion.ingestion.job.IngestionJobRunner;
import com.edupulse.ingestion.ingestion.pipeline.IngestionPipeline;
import com.edupulse.ingestion.repository.DocumentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jobrunr.scheduling.JobScheduler;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Upload flow: validate → SHA-256 (no duplicates in the same org unit) → store the file encrypted →
 * process it right away (≤ 10 MB) or as a JobRunr background job (&gt; 10 MB).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentService {

    private final DocumentRepository documents;
    private final UploadValidator validator;
    private final StorageService storage;
    private final IngestionPipeline pipeline;
    private final IngestionJobRunner jobRunner;
    private final JobScheduler jobScheduler;
    private final IngestionProperties props;

    public record UploadResult(DocumentRecord document, boolean async) {
    }

    public UploadResult upload(MultipartFile file, DocumentCategory category, String docType, Integer clearance,
                               String title, TenantContext tenant) throws IOException {
        int clr = clearance == null ? 1 : clearance;
        if (clr < 1 || clr > tenant.clearanceLevel()) {
            throw ApiException.badRequest("clearanceLevel must be between 1 and your own clearance (" + tenant.clearanceLevel() + ")");
        }
        String filename = UploadValidator.sanitizeFilename(file.getOriginalFilename());
        String mime;
        try (InputStream in = new BufferedInputStream(file.getInputStream())) {
            mime = validator.validate(filename, file.getSize(), in);
        }
        String sha = sha256(file);
        documents.findActiveBySha(tenant.orgUnitId(), sha).ifPresent(existing -> {
            throw ApiException.conflict("This document was already uploaded (id " + existing.id() + ")");
        });

        UUID id = UUID.randomUUID();
        String path;
        try (InputStream in = file.getInputStream()) {
            path = storage.store(tenant.organizationId(), id, in);
        }
        DocumentRecord record = new DocumentRecord(id, tenant.organizationId(), tenant.orgUnitId(),
                title == null || title.isBlank() ? stripExtension(filename) : title.strip(),
                filename, mime, file.getSize(), sha, category, docType, clr, 1, DocumentStatus.UPLOADED, 0,
                null, path, null, null, null, tenant.userId(), Instant.now(), null);
        documents.insert(record);
        log.info("Uploaded {} ({} bytes) as {} by {}", filename, file.getSize(), id, tenant.email());

        if (file.getSize() <= props.asyncThresholdBytes()) {
            try {
                pipeline.process(id);
            } catch (RuntimeException e) {
                // status FAILED and the error message are saved by the pipeline and returned to the client
            }
            return new UploadResult(documents.findById(id).orElseThrow(), false);
        }
        documents.updateStatus(id, DocumentStatus.QUEUED, 0);
        jobScheduler.enqueue(() -> jobRunner.run(id));
        log.info("Document {} is larger than 10 MB: queued for background ingestion", id);
        return new UploadResult(documents.findById(id).orElseThrow(), true);
    }

    public void delete(UUID id, TenantContext tenant) {
        DocumentRecord doc = visibleOrThrow(id, tenant);
        documents.markDeleted(doc.id());
        documents.deleteVectors(doc.id());
        documents.deleteParents(doc.id());
        try {
            storage.delete(doc.storagePath());
        } catch (IOException e) {
            log.warn("Could not delete stored file for {}: {}", doc.id(), e.getMessage());
        }
    }

    public DocumentRecord visibleOrThrow(UUID id, TenantContext tenant) {
        return documents.findById(id).filter(d -> DocumentRepository.visible(d, tenant))
                .orElseThrow(() -> ApiException.notFound("Document"));
    }

    private static String sha256(MultipartFile file) throws IOException {
        try (DigestInputStream in = new DigestInputStream(file.getInputStream(), MessageDigest.getInstance("SHA-256"))) {
            in.transferTo(OutputStream.nullOutputStream());
            return HexFormat.of().formatHex(in.getMessageDigest().digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }
}
