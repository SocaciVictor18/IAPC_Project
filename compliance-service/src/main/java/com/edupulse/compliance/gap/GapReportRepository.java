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

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class GapReportRepository {

    private final JdbcClient jdbc;
    private final JsonMapper json = JsonMapper.builder().build();

    public void create(UUID id, TenantContext t, UUID regulationId, String regulationTitle) {
        jdbc.sql("""
                        insert into compliance.gap_report (id, organization_id, org_unit_id, regulation_document_id,
                            regulation_title, requested_by_email, status)
                        values (?,?,?,?,?,?,'RUNNING')
                        """)
                .params(id, t.organizationId(), t.orgUnitId(), regulationId, regulationTitle, t.email())
                .update();
    }

    public void progress(UUID id, int progress) {
        jdbc.sql("update compliance.gap_report set progress_pct = ? where id = ?").params(progress, id).update();
    }

    public void complete(UUID id, int total, int covered, int partial, int gaps, String summary) {
        jdbc.sql("""
                        update compliance.gap_report set status = 'COMPLETED', progress_pct = 100, total_requirements = ?,
                            covered = ?, partial = ?, gaps = ?, summary = ?, finished_at = now() where id = ?
                        """)
                .params(total, covered, partial, gaps, summary, id).update();
    }

    public void fail(UUID id, String error) {
        jdbc.sql("update compliance.gap_report set status = 'FAILED', summary = ?, finished_at = now() where id = ?")
                .params(error, id).update();
    }

    public void insertFinding(UUID reportId, Finding f) {
        jdbc.sql("""
                        insert into compliance.gap_finding (id, report_id, ordinal, requirement_ref, requirement_text,
                            requirement_page, status, severity, rationale, recommendation, evidence)
                        values (?,?,?,?,?,?,?,?,?,?,?::jsonb)
                        """)
                .params(f.id(), reportId, f.ordinal(), f.requirementRef(), f.requirementText(), f.requirementPage(),
                        f.status(), f.severity(), f.rationale(), f.recommendation(), json.writeValueAsString(f.evidence()))
                .update();
    }

    /** Same organization; ADMIN sees all reports, the others only those of their org unit. */
    public Optional<Map<String, Object>> find(UUID id, TenantContext t) {
        return list(t).stream().filter(r -> id.equals(r.get("id"))).findFirst();
    }

    public List<Map<String, Object>> list(TenantContext t) {
        if (t.hasRole(Roles.ADMIN)) {
            return jdbc.sql("select * from compliance.gap_report where organization_id = ? order by created_at desc")
                    .param(t.organizationId()).query().listOfRows();
        }
        return jdbc.sql("select * from compliance.gap_report where org_unit_id = ? order by created_at desc")
                .param(t.orgUnitId()).query().listOfRows();
    }

    public List<Finding> findings(UUID reportId) {
        return jdbc.sql("select * from compliance.gap_finding where report_id = ? order by ordinal")
                .param(reportId)
                .query((rs, n) -> new Finding(
                        rs.getObject("id", UUID.class), rs.getInt("ordinal"), rs.getString("requirement_ref"),
                        rs.getString("requirement_text"), (Integer) rs.getObject("requirement_page"),
                        rs.getString("status"), rs.getString("severity"), rs.getString("rationale"),
                        rs.getString("recommendation"),
                        json.readValue(rs.getString("evidence"), new TypeReference<List<Evidence>>() { })))
                .list();
    }
}
