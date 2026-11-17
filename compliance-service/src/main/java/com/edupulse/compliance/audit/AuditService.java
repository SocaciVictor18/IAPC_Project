package com.edupulse.compliance.audit;

import com.edupulse.common.security.Roles;
import com.edupulse.common.security.TenantContext;
import com.edupulse.common.web.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Audit trail (FR "Conversation History Tracking"): every question – answered, refused or failed –
 * is saved together with the context chunks that were given to the LLM.
 */
@Service
@RequiredArgsConstructor
public class AuditService {

    private final JdbcClient jdbc;
    private final JsonMapper json = JsonMapper.builder().build();

    @Transactional
    public UUID record(AuditEntry e) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        insert into compliance.query_audit (id, conversation_id, user_id, user_email, organization_id, org_unit_id,
                            roles, question, transformed_query, expanded_queries, applied_filter, answer, latency_ms, outcome)
                        values (?,?,?,?,?,?,?,?,?,?::jsonb,?,?,?,?)
                        """)
                .params(id, e.conversationId(), e.userId(), e.userEmail(), e.organizationId(), e.orgUnitId(), e.roles(),
                        e.question(), e.transformedQuery(), json.writeValueAsString(e.expandedQueries()),
                        e.appliedFilter(), e.answer(), e.latencyMs(), e.outcome().name())
                .update();
        for (AuditEntry.Chunk c : e.chunks()) {
            jdbc.sql("""
                            insert into compliance.query_audit_chunk (audit_id, rank, label, chunk_id, document_id, source,
                                page_number, rerank_score, cited, snippet)
                            values (?,?,?,?,?,?,?,?,?,?)
                            """)
                    .params(id, c.rank(), c.label(), c.chunkId(), c.documentId(), c.source(), c.pageNumber(),
                            c.rerankScore(), c.cited(), c.snippet())
                    .update();
        }
        return id;
    }

    // ------------------------------------------------------------------ conversations

    public UUID ensureConversation(UUID conversationId, TenantContext t, String question) {
        if (conversationId != null) {
            boolean own = jdbc.sql("select count(*) from compliance.conversation where id = ? and user_id = ?")
                    .params(conversationId, t.userId()).query(Integer.class).single() > 0;
            if (!own) {
                throw ApiException.notFound("Conversation");
            }
            return conversationId;
        }
        UUID id = UUID.randomUUID();
        jdbc.sql("insert into compliance.conversation (id, user_id, title) values (?,?,?)")
                .params(id, t.userId(), question.length() > 80 ? question.substring(0, 80) + "…" : question).update();
        return id;
    }

    public record Turn(String question, String answer) {
    }

    /** Last answered turns of a conversation, oldest first. */
    public List<Turn> history(UUID conversationId, int turns) {
        List<Turn> latest = new ArrayList<>(jdbc.sql("""
                        select question, answer from compliance.query_audit
                        where conversation_id = ? and outcome = 'ANSWERED' order by created_at desc limit ?
                        """)
                .params(conversationId, turns)
                .query((rs, n) -> new Turn(rs.getString("question"), rs.getString("answer"))).list());
        Collections.reverse(latest);
        return latest;
    }

    public List<Map<String, Object>> conversations(TenantContext t) {
        return jdbc.sql("select id, title, created_at from compliance.conversation where user_id = ? order by created_at desc limit 100")
                .param(t.userId()).query().listOfRows();
    }

    public List<Map<String, Object>> messages(UUID conversationId, TenantContext t) {
        ensureConversation(conversationId, t, "");
        return jdbc.sql("select id, question, answer, outcome, created_at from compliance.query_audit where conversation_id = ? order by created_at")
                .param(conversationId).query().listOfRows();
    }

    // ------------------------------------------------------------------ audit log

    /** Who sees what: ADMIN – whole organization, COMPLIANCE_OFFICER / AUDITOR – own org unit, EMPLOYEE – own questions. */
    public List<Map<String, Object>> search(TenantContext t, int limit) {
        String where;
        Object param;
        if (t.hasRole(Roles.ADMIN)) {
            where = "organization_id = ?";
            param = t.organizationId();
        } else if (t.hasAnyRole(Roles.COMPLIANCE_OFFICER, Roles.AUDITOR)) {
            where = "org_unit_id = ?";
            param = t.orgUnitId();
        } else {
            where = "user_id = ?";
            param = t.userId();
        }
        return jdbc.sql("select id, user_email, question, outcome, latency_ms, created_at from compliance.query_audit where "
                        + where + " order by created_at desc limit ?")
                .params(param, limit).query().listOfRows();
    }

    public Optional<Map<String, Object>> detail(UUID id, TenantContext t) {
        return search(t, 10_000).stream().filter(r -> id.equals(r.get("id"))).findFirst().map(r -> {
            Map<String, Object> out = new LinkedHashMap<>(jdbc.sql("select * from compliance.query_audit where id = ?")
                    .param(id).query().singleRow());
            out.put("expanded_queries", json.readValue(String.valueOf(out.get("expanded_queries")), List.class));
            out.put("chunks", jdbc.sql("select * from compliance.query_audit_chunk where audit_id = ? order by rank")
                    .param(id).query().listOfRows());
            return out;
        });
    }
}
