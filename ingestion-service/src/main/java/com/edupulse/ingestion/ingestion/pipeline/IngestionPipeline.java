package com.edupulse.ingestion.ingestion.pipeline;

import com.edupulse.common.MetadataKeys;
import com.edupulse.common.crypto.AesGcmCipher;
import com.edupulse.ingestion.config.IngestionProperties;
import com.edupulse.ingestion.domain.DocumentRecord;
import com.edupulse.ingestion.domain.DocumentStatus;
import com.edupulse.ingestion.ingestion.chunking.ChildChunk;
import com.edupulse.ingestion.ingestion.chunking.HierarchicalChunker;
import com.edupulse.ingestion.ingestion.chunking.ParentChunk;
import com.edupulse.ingestion.ingestion.reader.DocumentParser;
import com.edupulse.ingestion.ingestion.reader.ParsedPage;
import com.edupulse.ingestion.repository.DocumentRepository;
import com.edupulse.ingestion.service.StorageService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * PARSING → CHUNKING → EMBEDDING → INDEXED (or FAILED). Idempotent: everything previously indexed
 * for the document is removed first, so re-index is a clean upsert.
 */
@Slf4j
@Component
public class IngestionPipeline {

    static final int SNIPPET_CHARS = 300;

    private final DocumentRepository documents;
    private final StorageService storage;
    private final DocumentParser parser;
    private final EmbeddingWriter embeddingWriter;
    private final AesGcmCipher cipher;
    private final MeterRegistry meters;
    private final TransactionTemplate tx;
    private final HierarchicalChunker chunker;

    public IngestionPipeline(DocumentRepository documents, StorageService storage, DocumentParser parser,
                             EmbeddingWriter embeddingWriter, AesGcmCipher cipher, IngestionProperties props,
                             MeterRegistry meters, TransactionTemplate tx) {
        this.documents = documents;
        this.storage = storage;
        this.parser = parser;
        this.embeddingWriter = embeddingWriter;
        this.cipher = cipher;
        this.meters = meters;
        this.tx = tx;
        IngestionProperties.Chunking c = props.chunking();
        this.chunker = new HierarchicalChunker(c.parentTokens(), c.parentMinChars(), c.childTokens(),
                c.childMinChars(), c.childOverlapWords());
    }

    public void process(UUID documentId) {
        DocumentRecord doc = documents.findById(documentId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown document " + documentId));
        if (doc.status() == DocumentStatus.DELETED) {
            log.info("Skipping deleted document {}", documentId);
            return;
        }
        Timer.Sample sample = Timer.start(meters);
        Path tmp = null;
        try {
            documents.updateStatus(documentId, DocumentStatus.PARSING, 5);
            tmp = storage.decryptToTemp(doc.storagePath(), suffix(doc.mimeType()));
            List<ParsedPage> pages = parser.parse(tmp, doc.mimeType());
            if (pages.isEmpty()) {
                throw new IllegalStateException("No extractable text (scanned PDF without OCR?)");
            }

            documents.updateStatus(documentId, DocumentStatus.CHUNKING, 25);
            HierarchicalChunker.Result result = chunker.chunk(pages);
            tx.executeWithoutResult(s -> {
                documents.deleteVectors(documentId);
                documents.deleteParents(documentId);
                for (ParentChunk p : result.parents()) {
                    documents.insertParent(p.id(), documentId, p.index(), p.pageNumber(), p.sectionRef(),
                            cipher.encryptString(p.text()), p.approxTokens());
                }
            });

            documents.updateStatus(documentId, DocumentStatus.EMBEDDING, 35);
            List<Document> childDocs = result.children().stream().map(c -> toDocument(doc, c)).toList();
            int total = childDocs.size();
            embeddingWriter.write(childDocs, done ->
                    documents.updateStatus(documentId, DocumentStatus.EMBEDDING, 35 + (int) (60.0 * done / Math.max(1, total))));

            int pageCount = (int) pages.stream()
                    .map(p -> p.pageNumber() == null ? p.sectionRef() : String.valueOf(p.pageNumber()))
                    .distinct().count();
            documents.markIndexed(documentId, pageCount, result.parents().size(), total);
            documents.jobFinished(documentId, "SUCCEEDED", null);
            meters.counter("ingestion.documents", "outcome", "indexed").increment();
            log.info("Indexed document {} ({}): {} pages, {} parents, {} children",
                    documentId, doc.originalFilename(), pageCount, result.parents().size(), total);
        } catch (Exception e) {
            log.error("Ingestion failed for {}", documentId, e);
            documents.markFailed(documentId, e.getClass().getSimpleName() + ": " + e.getMessage());
            documents.jobFinished(documentId, "FAILED", e.getMessage());
            meters.counter("ingestion.documents", "outcome", "failed").increment();
            throw e instanceof RuntimeException re ? re : new IllegalStateException(e);
        } finally {
            sample.stop(meters.timer("ingestion.pipeline.duration"));
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (Exception ignored) {
                    // best effort
                }
            }
        }
    }

    private Document toDocument(DocumentRecord doc, ChildChunk c) {
        Map<String, Object> md = new HashMap<>();
        md.put(MetadataKeys.ORGANIZATION_ID, doc.organizationId().toString());
        md.put(MetadataKeys.ORG_UNIT_ID, doc.orgUnitId().toString());
        md.put(MetadataKeys.CLEARANCE_LEVEL, doc.clearanceLevel());
        md.put(MetadataKeys.DOCUMENT_ID, doc.id().toString());
        md.put(MetadataKeys.DOCUMENT_VERSION, doc.version());
        md.put(MetadataKeys.SOURCE, doc.originalFilename());
        md.put(MetadataKeys.TITLE, doc.title() != null ? doc.title() : doc.originalFilename());
        md.put(MetadataKeys.CATEGORY, doc.category().name());
        if (doc.docType() != null) {
            md.put(MetadataKeys.DOC_TYPE, doc.docType());
        }
        md.put(MetadataKeys.UPLOADED_AT, doc.uploadedAt().toEpochMilli());
        if (c.pageNumber() != null) {
            md.put(MetadataKeys.PAGE_NUMBER, c.pageNumber());
        }
        if (c.sectionRef() != null) {
            md.put(MetadataKeys.SECTION_REF, c.sectionRef());
        }
        md.put(MetadataKeys.PARENT_ID, c.parentId().toString());
        md.put(MetadataKeys.PARENT_INDEX, c.parentIndex());
        md.put(MetadataKeys.CHUNK_INDEX, c.index());
        md.put(MetadataKeys.SNIPPET, snippet(c.ownText(), SNIPPET_CHARS));
        return Document.builder().id(c.id().toString()).text(c.text()).metadata(md).build();
    }

    static String snippet(String text, int max) {
        String oneLine = text.replaceAll("\\s+", " ").strip();
        return oneLine.length() <= max ? oneLine : oneLine.substring(0, max).strip() + "…";
    }

    private static String suffix(String mime) {
        return switch (mime) {
            case DocumentParser.MIME_PDF -> ".pdf";
            case DocumentParser.MIME_DOCX -> ".docx";
            default -> ".txt";
        };
    }
}
