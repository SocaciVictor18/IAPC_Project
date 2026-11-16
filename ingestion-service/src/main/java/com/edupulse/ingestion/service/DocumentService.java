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
import org.jobrunr.jobs.JobId;
import org.jobrunr.scheduling.JobScheduler;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Upload orchestration: validate → hash/dedup → encrypted storage → index synchronously
 * (≤ 10 MB) or enqueue a background JobRunr job (&gt; 10 MB, FR "Asynchronous Parsing").
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentService {

    private static final Pattern DOC_TYPE = Pattern.compile("[\\p{L}0-9 _.-]{1,64}");

    private final DocumentRepository documents;
    private final UploadValidator validator;
    private final StorageService storage;
    private final IngestionPipeline pipeline;
    private final IngestionJobRunner jobRunner;
    private final JobScheduler jobScheduler;
    private final IngestionProperties props;

    public record UploadResult(DocumentRecord document, boolean async, UUID jobId) {
    }

    public UploadResult upload(MultipartFile file, DocumentCategory category, String docType, Integer clearance,
                               String title, boolean replace, TenantContext tenant) throws IOException {
        int clr = clearance == null ? 1 : clearance;
        if (clr < 1 || clr > tenant.clearanceLevel()) {
            throw ApiException.badRequest("clearanceLevel must be between 1 and your own clearance (" + tenant.clearanceLevel() + ")");
        }
        if (docType != null && !DOC_TYPE.matcher(docType).matches()) {
            throw ApiException.badRequest("docType may contain letters, digits, space, '_', '-', '.' (max 64)");
        }
        String filename = UploadValidator.sanitizeFilename(file.getOriginalFilename());
        String mime;
        try (InputStream in = new BufferedInputStream(file.getInputStream())) {
            mime = validator.validate(filename, file.getSize(), in);
        }
        if (documents.countQueued() >= props.maxQueuedJobs()) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "Ingestion queue is full, retry later");
        }
        String sha = sha256(file);

        int version = 1;
        var existing = documents.findActiveBySha(tenant.orgUnitId(), sha);
        if (existing.isPresent()) {
            if (!replace) {
                throw ApiException.conflict("Identical document already uploaded (id " + existing.get().id()
                        + "). Use replace=true to upload a new version.");
            }
            delete(existing.get());
            version = documents.maxVersion(tenant.orgUnitId(), sha) + 1;
        }

        UUID id = UUID.randomUUID();
        String path;
        try (InputStream in = file.getInputStream()) {
            path = storage.store(tenant.organizationId(), id, in);
        }
        DocumentRecord record = new DocumentRecord(id, tenant.organizationId(), tenant.orgUnitId(),
                title == null || title.isBlank() ? stripExtension(filename) : title.strip(),
                filename, mime, file.getSize(), sha, category, docType, clr, version, DocumentStatus.UPLOADED, 0,
                null, path, null, null, null, tenant.userId(), Instant.now(), null);
        documents.insert(record);
        log.info("Uploaded {} ({} bytes, {}) as {} by {}", filename, file.getSize(), category, id, tenant.email());
        return dispatch(record);
    }

    public UploadResult reindex(UUID id, TenantContext tenant) {
        DocumentRecord doc = visibleOrThrow(id, tenant);
        if (doc.status().inProgress()) {
            throw ApiException.conflict("Document is already being processed (" + doc.status() + ")");
        }
        return dispatch(doc);
    }

    public void delete(UUID id, TenantContext tenant) {
        delete(visibleOrThrow(id, tenant));
    }

    public DocumentRecord visibleOrThrow(UUID id, TenantContext tenant) {
        return documents.findById(id).filter(d -> DocumentRepository.visible(d, tenant))
                .orElseThrow(() -> ApiException.notFound("Document"));
    }

    private UploadResult dispatch(DocumentRecord doc) {
        if (doc.sizeBytes() <= props.asyncThresholdBytes()) {
            documents.createJob(doc.id(), "SYNC");
            documents.jobStarted(doc.id());
            try {
                pipeline.process(doc.id());
            } catch (RuntimeException e) {
                // status FAILED + error are persisted by the pipeline and returned to the client
            }
            return new UploadResult(documents.findById(doc.id()).orElseThrow(), false, null);
        }
        documents.updateStatus(doc.id(), DocumentStatus.QUEUED, 0);
        UUID jobId = documents.createJob(doc.id(), "ASYNC");
        UUID documentId = doc.id();
        JobId jobRunrId = jobScheduler.enqueue(() -> jobRunner.run(documentId));
        documents.attachJobRunrId(jobId, jobRunrId.asUUID());
        log.info("Document {} ({} bytes) queued for asynchronous ingestion (job {})", documentId, doc.sizeBytes(), jobRunrId);
        return new UploadResult(documents.findById(documentId).orElseThrow(), true, jobId);
    }

    private void delete(DocumentRecord doc) {
        documents.markDeleted(doc.id());
        documents.deleteVectors(doc.id());
        documents.deleteParents(doc.id());
        try {
            storage.delete(doc.storagePath());
        } catch (IOException e) {
            log.warn("Could not delete stored file for {}: {}", doc.id(), e.getMessage());
        }
        log.info("Deleted document {} ({})", doc.id(), doc.originalFilename());
    }

    private static String sha256(MultipartFile file) throws IOException {
        try (DigestInputStream in = new DigestInputStream(file.getInputStream(), MessageDigest.getInstance("SHA-256"))) {
            in.transferTo(java.io.OutputStream.nullOutputStream());
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
