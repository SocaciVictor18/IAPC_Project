package com.edupulse.compliance.gap;

import com.edupulse.common.security.Roles;
import com.edupulse.common.security.TenantContext;
import com.edupulse.compliance.gap.GapModels.Evidence;
import com.edupulse.compliance.gap.GapModels.Finding;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class GapReportRepository {

    private static final TypeReference<List<Evidence>> EVIDENCE = new TypeReference<>() {
    };

    private final JdbcClient jdbc;
    private final JsonMapper json = JsonMapper.builder().build();

    public void create(UUID id, TenantContext t, UUID regulationId, String regulationTitle, GapModels.Scope scope) {
        jdbc.sql("""
                        insert into compliance.gap_report (id, organization_id, org_unit_id, regulation_document_id, regulation_title,
                            requested_by, requested_by_email, requester_clearance, requester_roles, scope, status)
                        values (?,?,?,?,?,?,?,?,?,?::jsonb,'QUEUED')
                        """)
                .params(id, t.organizationId(), t.orgUnitId(), regulationId, regulationTitle, t.userId(), t.email(),
                        t.clearanceLevel(), String.join(",", t.roles()), json.writeValueAsString(scope))
                .update();
    }

    public void status(UUID id, String status, int progress) {
        jdbc.sql("update compliance.gap_report set status = ?, progress_pct = ? where id = ?").params(status, progress, id).update();
    }

    public void progress(UUID id, int progress) {
        jdbc.sql("update compliance.gap_report set progress_pct = ? where id = ?").params(progress, id).update();
    }

    public void complete(UUID id, int total, int covered, int partial, int gaps, int na, String summary) {
        jdbc.sql("""
                        update compliance.gap_report set status = 'COMPLETED', progress_pct = 100, total_requirements = ?, covered = ?,
                            partial = ?, gaps = ?, not_applicable = ?, summary = ?, finished_at = now() where id = ?
                        """)
                .params(total, covered, partial, gaps, na, summary, id).update();
    }

    public void fail(UUID id, String error) {
        jdbc.sql("update compliance.gap_report set status = 'FAILED', error = ?, finished_at = now() where id = ?")
                .params(error == null ? "unknown error" : error.substring(0, Math.min(2000, error.length())), id).update();
    }

    /** Reports interrupted by a restart can never finish: mark them failed at startup. */
    public int failInterrupted() {
        return jdbc.sql("""
                update compliance.gap_report set status = 'FAILED', error = 'Interrupted by service restart', finished_at = now()
                where status in ('QUEUED', 'RUNNING')
                """).update();
    }

    public void insertFinding(UUID reportId, Finding f) {
        jdbc.sql("""
                        insert into compliance.gap_finding (id, report_id, ordinal, requirement_ref, requirement_text, requirement_page,
                            obligation_type, status, severity, rationale, recommendation, evidence)
                        values (?,?,?,?,?,?,?,?,?,?,?,?::jsonb)
                        """)
                .params(f.id(), reportId, f.ordinal(), f.requirementRef(), f.requirementText(), f.requirementPage(),
                        f.obligationType(), f.status(), f.severity(), f.rationale(), f.recommendation(),
                        json.writeValueAsString(f.evidence()))
                .update();
    }

    /** Visibility: same organization; ADMIN org-wide, CO/AUDITOR their org unit (employees have no access). */
    public Optional<Map<String, Object>> find(UUID id, TenantContext t) {
        return jdbc.sql("select * from compliance.gap_report where id = ? and organization_id = ?")
                .params(id, t.organizationId()).query().listOfRows().stream().findFirst()
                .filter(r -> t.hasRole(Roles.ADMIN) || t.orgUnitId().equals(r.get("org_unit_id")));
    }

    public List<Map<String, Object>> list(TenantContext t) {
        String sql = "select id, regulation_document_id, regulation_title, requested_by_email, status, progress_pct, total_requirements,"
                + " covered, partial, gaps, not_applicable, created_at, finished_at from compliance.gap_report where organization_id = ?";
        List<Object> params = new ArrayList<>(List.of(t.organizationId()));
        if (!t.hasRole(Roles.ADMIN)) {
            sql += " and org_unit_id = ?";
            params.add(t.orgUnitId());
        }
        return jdbc.sql(sql + " order by created_at desc limit 100").params(params).query().listOfRows();
    }

    public List<Finding> findings(UUID reportId, String status, String severity) {
        String sql = "select * from compliance.gap_finding where report_id = ?";
        List<Object> params = new ArrayList<>(List.of(reportId));
        if (status != null) {
            sql += " and status = ?";
            params.add(status);
        }
        if (severity != null) {
            sql += " and severity = ?";
            params.add(severity);
        }
        return jdbc.sql(sql + " order by ordinal").params(params)
                .query((rs, n) -> new Finding(
                        rs.getObject("id", UUID.class), rs.getInt("ordinal"), rs.getString("requirement_ref"),
                        rs.getString("requirement_text"), (Integer) rs.getObject("requirement_page"),
                        rs.getString("obligation_type"), rs.getString("status"), rs.getString("severity"),
                        rs.getString("rationale"), rs.getString("recommendation"),
                        rs.getString("evidence") == null ? List.of() : json.readValue(rs.getString("evidence"), EVIDENCE)))
                .list();
    }
}
