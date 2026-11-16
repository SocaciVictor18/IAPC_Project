package com.edupulse.ingestion.repository;

import com.edupulse.common.security.TenantContext;
import com.edupulse.ingestion.domain.DocumentCategory;
import com.edupulse.ingestion.domain.DocumentRecord;
import com.edupulse.ingestion.domain.DocumentStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class DocumentRepository {

    private final JdbcClient jdbc;

    public void insert(DocumentRecord d) {
        jdbc.sql("""
                        insert into ingestion.document (id, organization_id, org_unit_id, title, original_filename, mime_type,
                            size_bytes, sha256, category, doc_type, clearance_level, version, status, storage_path, uploaded_by)
                        values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                        """)
                .params(d.id(), d.organizationId(), d.orgUnitId(), d.title(), d.originalFilename(), d.mimeType(),
                        d.sizeBytes(), d.sha256(), d.category().name(), d.docType(), d.clearanceLevel(), d.version(),
                        d.status().name(), d.storagePath(), d.uploadedBy())
                .update();
    }

    public Optional<DocumentRecord> findById(UUID id) {
        return jdbc.sql("select * from ingestion.document where id = ?").param(id).query(DocumentRepository::map).optional();
    }

    /** Same document already uploaded to this org unit (any non-deleted version). */
    public Optional<DocumentRecord> findActiveBySha(UUID orgUnitId, String sha256) {
        return jdbc.sql("""
                        select * from ingestion.document
                        where org_unit_id = ? and sha256 = ? and status <> 'DELETED'
                        order by version desc limit 1
                        """)
                .params(orgUnitId, sha256).query(DocumentRepository::map).optional();
    }

    public int maxVersion(UUID orgUnitId, String sha256) {
        return jdbc.sql("select coalesce(max(version), 0) from ingestion.document where org_unit_id = ? and sha256 = ?")
                .params(orgUnitId, sha256).query(Integer.class).single();
    }

    /** Documents visible to a user: same tenant scope as the retrieval filter (org-wide for admins). */
    public List<DocumentRecord> findVisible(TenantContext t, DocumentCategory category, int limit, int offset) {
        StringBuilder sql = new StringBuilder("""
                select * from ingestion.document
                where organization_id = ? and status <> 'DELETED' and clearance_level <= ?
                """);
        List<Object> params = new ArrayList<>(List.of(t.organizationId(), t.clearanceLevel()));
        if (!t.organizationWide()) {
            sql.append(" and org_unit_id = ?");
            params.add(t.orgUnitId());
        }
        if (category != null) {
            sql.append(" and category = ?");
            params.add(category.name());
        }
        sql.append(" order by uploaded_at desc limit ? offset ?");
        params.add(limit);
        params.add(offset);
        return jdbc.sql(sql.toString()).params(params).query(DocumentRepository::map).list();
    }

    public long countVisible(TenantContext t, DocumentCategory category) {
        StringBuilder sql = new StringBuilder("""
                select count(*) from ingestion.document
                where organization_id = ? and status <> 'DELETED' and clearance_level <= ?
                """);
        List<Object> params = new ArrayList<>(List.of(t.organizationId(), t.clearanceLevel()));
        if (!t.organizationWide()) {
            sql.append(" and org_unit_id = ?");
            params.add(t.orgUnitId());
        }
        if (category != null) {
            sql.append(" and category = ?");
            params.add(category.name());
        }
        return jdbc.sql(sql.toString()).params(params).query(Long.class).single();
    }

    /** Same rule as the RAG tenant filter: own org, own OU (unless admin), clearance not above the user's. */
    public static boolean visible(DocumentRecord d, TenantContext t) {
        return d.status() != DocumentStatus.DELETED
                && d.organizationId().equals(t.organizationId())
                && (t.organizationWide() || d.orgUnitId().equals(t.orgUnitId()))
                && d.clearanceLevel() <= t.clearanceLevel();
    }

    public int countQueued() {
        return jdbc.sql("select count(*) from ingestion.document where status in ('QUEUED','PARSING','CHUNKING','EMBEDDING')")
                .query(Integer.class).single();
    }

    public void updateStatus(UUID id, DocumentStatus status, int progressPct) {
        jdbc.sql("update ingestion.document set status = ?, progress_pct = ?, error_message = null where id = ?")
                .params(status.name(), progressPct, id).update();
    }

    public void markFailed(UUID id, String error) {
        jdbc.sql("update ingestion.document set status = 'FAILED', error_message = ? where id = ?")
                .params(truncate(error, 2000), id).update();
    }

    public void markIndexed(UUID id, int pageCount, int parentCount, int chunkCount) {
        jdbc.sql("""
                        update ingestion.document set status = 'INDEXED', progress_pct = 100, page_count = ?, parent_count = ?,
                            chunk_count = ?, indexed_at = now(), error_message = null where id = ?
                        """)
                .params(pageCount, parentCount, chunkCount, id).update();
    }

    public void markDeleted(UUID id) {
        jdbc.sql("update ingestion.document set status = 'DELETED' where id = ?").param(id).update();
    }

    // --- jobs ---

    public UUID createJob(UUID documentId, String mode) {
        UUID id = UUID.randomUUID();
        jdbc.sql("insert into ingestion.ingestion_job (id, document_id, mode, status) values (?,?,?, 'QUEUED')")
                .params(id, documentId, mode).update();
        return id;
    }

    public void attachJobRunrId(UUID jobId, UUID jobRunrId) {
        jdbc.sql("update ingestion.ingestion_job set jobrunr_job_id = ? where id = ?").params(jobRunrId, jobId).update();
    }

    public void jobStarted(UUID documentId) {
        jdbc.sql("""
                        update ingestion.ingestion_job set status = 'RUNNING', started_at = now()
                        where document_id = ? and finished_at is null
                        """)
                .param(documentId).update();
    }

    public void jobFinished(UUID documentId, String status, String error) {
        jdbc.sql("""
                        update ingestion.ingestion_job set status = ?, finished_at = now(), error = ?
                        where document_id = ? and finished_at is null
                        """)
                .params(status, truncate(error, 2000), documentId).update();
    }

    public Optional<UUID> latestJobRunrId(UUID documentId) {
        return jdbc.sql("select jobrunr_job_id from ingestion.ingestion_job where document_id = ? order by created_at desc limit 1")
                .param(documentId).query(UUID.class).optional();
    }

    // --- parents ---

    public void insertParent(UUID id, UUID documentId, int parentIndex, Integer pageNumber, String sectionRef,
                             byte[] contentEnc, int tokenCount) {
        jdbc.sql("""
                        insert into ingestion.parent_chunk (id, document_id, parent_index, page_number, section_ref, content_enc, token_count)
                        values (?,?,?,?,?,?,?)
                        """)
                .params(id, documentId, parentIndex, pageNumber, sectionRef, contentEnc, tokenCount).update();
    }

    public void deleteParents(UUID documentId) {
        jdbc.sql("delete from ingestion.parent_chunk where document_id = ?").param(documentId).update();
    }

    public record ParentRow(UUID id, int parentIndex, Integer pageNumber, String sectionRef, byte[] contentEnc) {
    }

    public List<ParentRow> parents(UUID documentId) {
        return jdbc.sql("""
                        select id, parent_index, page_number, section_ref, content_enc from ingestion.parent_chunk
                        where document_id = ? order by parent_index
                        """)
                .param(documentId)
                .query((rs, n) -> new ParentRow(rs.getObject("id", UUID.class), rs.getInt("parent_index"),
                        (Integer) rs.getObject("page_number"), rs.getString("section_ref"), rs.getBytes("content_enc")))
                .list();
    }

    /** Removes the child chunks of a document from the vector table (cheaper than a vector-store filter delete). */
    public int deleteVectors(UUID documentId) {
        return jdbc.sql("delete from ingestion.rag_vector_store where metadata::jsonb ->> 'document_id' = ?")
                .param(documentId.toString()).update();
    }

    public int countVectors(UUID documentId) {
        return jdbc.sql("select count(*) from ingestion.rag_vector_store where metadata::jsonb ->> 'document_id' = ?")
                .param(documentId.toString()).query(Integer.class).single();
    }

    static DocumentRecord map(ResultSet rs, int n) throws SQLException {
        return new DocumentRecord(
                rs.getObject("id", UUID.class),
                rs.getObject("organization_id", UUID.class),
                rs.getObject("org_unit_id", UUID.class),
                rs.getString("title"),
                rs.getString("original_filename"),
                rs.getString("mime_type"),
                rs.getLong("size_bytes"),
                rs.getString("sha256"),
                DocumentCategory.valueOf(rs.getString("category")),
                rs.getString("doc_type"),
                rs.getInt("clearance_level"),
                rs.getInt("version"),
                DocumentStatus.valueOf(rs.getString("status")),
                rs.getInt("progress_pct"),
                rs.getString("error_message"),
                rs.getString("storage_path"),
                (Integer) rs.getObject("page_count"),
                (Integer) rs.getObject("parent_count"),
                (Integer) rs.getObject("chunk_count"),
                rs.getObject("uploaded_by", UUID.class),
                instant(rs.getTimestamp("uploaded_at")),
                instant(rs.getTimestamp("indexed_at")));
    }

    private static Instant instant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
