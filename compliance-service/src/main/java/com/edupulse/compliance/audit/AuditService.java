package com.edupulse.compliance.audit;

import com.edupulse.common.security.Roles;
import com.edupulse.common.security.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Append-only audit trail (FR "Conversation History Tracking"): every query – answered, refused or
 * failed – with the exact context chunks given to the LLM.
 * <p>
 * Tamper evidence: rows are hash-chained per organization ({@code row_hash = sha256(prev_hash | row)});
 * a DB trigger rejects UPDATE/DELETE, and {@link #verifyChain} recomputes the chain.
 */
@Service
@RequiredArgsConstructor
public class AuditService {

    private final JdbcClient jdbc;
    private final JsonMapper json = JsonMapper.builder().build();

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public UUID record(AuditEntry e) {
        UUID id = UUID.randomUUID();
        Instant createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        // Serialise writers of the same organization so the chain stays linear.
        jdbc.sql("select pg_advisory_xact_lock(hashtext(?))").param(e.organizationId().toString()).query((rs, n) -> 1).list();
        String prev = jdbc.sql("select row_hash from compliance.query_audit where organization_id = ? order by seq desc limit 1")
                .param(e.organizationId()).query(String.class).optional().orElse(null);
        List<String> chunkIds = e.chunks().stream().map(c -> String.valueOf(c.chunkId())).toList();
        String rowHash = sha256(canonical(prev, id, e.organizationId(), e.userId(), e.question(), e.answer(),
                e.outcome().name(), createdAt, chunkIds));

        jdbc.sql("""
                        insert into compliance.query_audit (id, conversation_id, user_id, user_email, organization_id, org_unit_id,
                            roles, question, transformed_query, expanded_queries, applied_filter, answer, model, prompt_tokens,
                            completion_tokens, latency_ms, ttft_ms, outcome, ungrounded, streamed, prev_hash, row_hash, created_at)
                        values (?,?,?,?,?,?,?,?,?,?::jsonb,?,?,?,?,?,?,?,?,?,?,?,?,?)
                        """)
                .params(id, e.conversationId(), e.userId(), e.userEmail(), e.organizationId(), e.orgUnitId(), e.roles(),
                        e.question(), e.transformedQuery(), json.writeValueAsString(e.expandedQueries() == null ? List.of() : e.expandedQueries()),
                        e.appliedFilter(), e.answer(), e.model(), e.promptTokens(), e.completionTokens(), e.latencyMs(),
                        e.ttftMs(), e.outcome().name(), e.ungrounded(), e.streamed(), prev, rowHash, Timestamp.from(createdAt))
                .update();
        for (AuditEntry.Chunk c : e.chunks()) {
            jdbc.sql("""
                            insert into compliance.query_audit_chunk (audit_id, rank, label, chunk_id, parent_id, document_id, source,
                                page_number, section_ref, retrieval_score, rerank_score, match, cited, snippet)
                            values (?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                            """)
                    .params(id, c.rank(), c.label(), c.chunkId(), c.parentId(), c.documentId(), c.source(), c.pageNumber(),
                            c.sectionRef(), c.retrievalScore(), c.rerankScore(), c.match(), c.cited(), c.snippet())
                    .update();
        }
        return id;
    }

    // ---------------------------------------------------------------- conversations

    public UUID ensureConversation(UUID conversationId, TenantContext t, String question) {
        if (conversationId != null) {
            boolean owned = jdbc.sql("select count(*) from compliance.conversation where id = ? and user_id = ?")
                    .params(conversationId, t.userId()).query(Integer.class).single() > 0;
            if (!owned) {
                throw com.edupulse.common.web.ApiException.notFound("Conversation");
            }
            return conversationId;
        }
        UUID id = UUID.randomUUID();
        String title = question.length() > 80 ? question.substring(0, 80) + "…" : question;
        jdbc.sql("insert into compliance.conversation (id, user_id, organization_id, org_unit_id, title) values (?,?,?,?,?)")
                .params(id, t.userId(), t.organizationId(), t.orgUnitId(), title).update();
        return id;
    }

    public record Turn(String question, String answer) {
    }

    /** Last answered turns of a conversation (oldest first), used as chat history. */
    public List<Turn> history(UUID conversationId, int turns) {
        if (conversationId == null || turns <= 0) {
            return List.of();
        }
        List<Turn> latest = jdbc.sql("""
                        select question, answer from compliance.query_audit
                        where conversation_id = ? and outcome = 'ANSWERED' order by seq desc limit ?
                        """)
                .params(conversationId, turns)
                .query((rs, n) -> new Turn(rs.getString("question"), rs.getString("answer"))).list();
        List<Turn> ordered = new ArrayList<>(latest);
        java.util.Collections.reverse(ordered);
        return ordered;
    }

    public List<Map<String, Object>> conversations(TenantContext t) {
        return jdbc.sql("""
                        select c.id, c.title, c.created_at, count(a.id) as messages, max(a.created_at) as last_message_at
                        from compliance.conversation c left join compliance.query_audit a on a.conversation_id = c.id
                        where c.user_id = ? group by c.id order by coalesce(max(a.created_at), c.created_at) desc limit 100
                        """)
                .param(t.userId()).query().listOfRows();
    }

    public List<Map<String, Object>> messages(UUID conversationId, TenantContext t) {
        ensureConversation(conversationId, t, "");
        return jdbc.sql("""
                        select id as audit_id, question, answer, outcome, created_at, latency_ms
                        from compliance.query_audit where conversation_id = ? order by seq
                        """)
                .param(conversationId).query().listOfRows();
    }

    // ---------------------------------------------------------------- audit queries

    public record AuditQuery(Instant from, Instant to, UUID userId, Outcome outcome, int page, int size) {
    }

    /** RBAC: ADMIN → whole organization; COMPLIANCE_OFFICER/AUDITOR → own org unit; EMPLOYEE → own queries. */
    public Map<String, Object> search(AuditQuery q, TenantContext t) {
        StringBuilder where = new StringBuilder(" where organization_id = ?");
        List<Object> params = new ArrayList<>(List.of(t.organizationId()));
        if (t.hasRole(Roles.ADMIN)) {
            // organization-wide
        } else if (t.hasAnyRole(Roles.COMPLIANCE_OFFICER, Roles.AUDITOR)) {
            where.append(" and org_unit_id = ?");
            params.add(t.orgUnitId());
        } else {
            where.append(" and user_id = ?");
            params.add(t.userId());
        }
        if (q.userId() != null) {
            where.append(" and user_id = ?");
            params.add(q.userId());
        }
        if (q.outcome() != null) {
            where.append(" and outcome = ?");
            params.add(q.outcome().name());
        }
        if (q.from() != null) {
            where.append(" and created_at >= ?");
            params.add(Timestamp.from(q.from()));
        }
        if (q.to() != null) {
            where.append(" and created_at < ?");
            params.add(Timestamp.from(q.to()));
        }
        long total = jdbc.sql("select count(*) from compliance.query_audit" + where).params(params).query(Long.class).single();
        List<Object> pageParams = new ArrayList<>(params);
        pageParams.add(q.size());
        pageParams.add((long) q.page() * q.size());
        List<Map<String, Object>> items = jdbc.sql("""
                        select id, conversation_id, user_id, user_email, org_unit_id, question, outcome, model, prompt_tokens,
                               completion_tokens, latency_ms, ttft_ms, ungrounded, streamed, created_at
                        from compliance.query_audit""" + where + " order by seq desc limit ? offset ?")
                .params(pageParams).query().listOfRows();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", items);
        body.put("total", total);
        body.put("page", q.page());
        body.put("size", q.size());
        return body;
    }

    public Optional<Map<String, Object>> detail(UUID id, TenantContext t) {
        Optional<Map<String, Object>> row = jdbc.sql("select * from compliance.query_audit where id = ? and organization_id = ?")
                .params(id, t.organizationId()).query().listOfRows().stream().findFirst();
        return row.filter(r -> canSee(r, t)).map(r -> {
            Map<String, Object> out = new LinkedHashMap<>(r);
            Object expanded = out.get("expanded_queries");
            if (expanded != null) {
                out.put("expanded_queries", json.readValue(expanded.toString(), List.class));
            }
            out.put("chunks", jdbc.sql("select * from compliance.query_audit_chunk where audit_id = ? order by rank")
                    .param(id).query().listOfRows());
            return out;
        });
    }

    private static boolean canSee(Map<String, Object> row, TenantContext t) {
        if (t.hasRole(Roles.ADMIN)) {
            return true;
        }
        if (t.hasAnyRole(Roles.COMPLIANCE_OFFICER, Roles.AUDITOR)) {
            return t.orgUnitId().equals(row.get("org_unit_id"));
        }
        return t.userId().equals(row.get("user_id"));
    }

    public String exportCsv(AuditQuery q, TenantContext t) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) search(
                new AuditQuery(q.from(), q.to(), q.userId(), q.outcome(), 0, 10_000), t).get("items");
        String header = "id,created_at,user_email,outcome,latency_ms,prompt_tokens,completion_tokens,question";
        return header + "\n" + items.stream().map(r -> String.join(",",
                        csv(r.get("id")), csv(r.get("created_at")), csv(r.get("user_email")), csv(r.get("outcome")),
                        csv(r.get("latency_ms")), csv(r.get("prompt_tokens")), csv(r.get("completion_tokens")), csv(r.get("question"))))
                .collect(Collectors.joining("\n")) + "\n";
    }

    public record ChainVerification(boolean valid, long rowsChecked, UUID firstBrokenId) {
    }

    public ChainVerification verifyChain(UUID organizationId) {
        List<Map<String, Object>> rows = jdbc.sql("""
                        select id, organization_id, user_id, question, answer, outcome, created_at, prev_hash, row_hash
                        from compliance.query_audit where organization_id = ? order by seq
                        """).param(organizationId).query().listOfRows();
        String prev = null;
        long checked = 0;
        for (Map<String, Object> r : rows) {
            UUID id = (UUID) r.get("id");
            List<String> chunkIds = jdbc.sql("select chunk_id from compliance.query_audit_chunk where audit_id = ? order by rank")
                    .param(id).query((rs, n) -> String.valueOf(rs.getObject("chunk_id"))).list();
            Instant createdAt = ((Timestamp) r.get("created_at")).toInstant();
            String expected = sha256(canonical(prev, id, (UUID) r.get("organization_id"), (UUID) r.get("user_id"),
                    (String) r.get("question"), (String) r.get("answer"), (String) r.get("outcome"), createdAt, chunkIds));
            String storedPrev = r.get("prev_hash") == null ? null : r.get("prev_hash").toString().trim();
            if (!expected.equals(String.valueOf(r.get("row_hash")).trim()) || !java.util.Objects.equals(prev, storedPrev)) {
                return new ChainVerification(false, checked, id);
            }
            prev = expected;
            checked++;
        }
        return new ChainVerification(true, checked, null);
    }

    static String canonical(String prev, UUID id, UUID org, UUID user, String question, String answer, String outcome,
                            Instant createdAt, List<String> chunkIds) {
        return String.join("\u001f", String.valueOf(prev), id.toString(), org.toString(), user.toString(),
                String.valueOf(question), String.valueOf(answer), outcome, createdAt.toString(), String.join(",", chunkIds));
    }

    static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static String csv(Object v) {
        if (v == null) {
            return "";
        }
        String s = v.toString().replace("\"", "\"\"");
        return s.contains(",") || s.contains("\n") || s.contains("\"") ? "\"" + s + "\"" : s;
    }
}
