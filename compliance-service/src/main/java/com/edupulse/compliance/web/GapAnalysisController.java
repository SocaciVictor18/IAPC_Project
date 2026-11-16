package com.edupulse.compliance.web;

import com.edupulse.common.security.TenantContext;
import com.edupulse.common.web.ApiException;
import com.edupulse.compliance.gap.GapAnalysisService;
import com.edupulse.compliance.gap.GapModels;
import com.edupulse.compliance.gap.GapModels.Finding;
import com.edupulse.compliance.gap.GapReportExporter;
import com.edupulse.compliance.gap.GapReportRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * RBAC: start → ADMIN, COMPLIANCE_OFFICER; read/export → ADMIN, COMPLIANCE_OFFICER, AUDITOR.
 */
@RestController
@RequestMapping("/api/gap-analysis")
@RequiredArgsConstructor
public class GapAnalysisController {

    private final GapAnalysisService service;
    private final GapReportRepository reports;
    private final GapReportExporter exporter;

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','COMPLIANCE_OFFICER')")
    public ResponseEntity<Map<String, Object>> start(@RequestBody GapModels.StartRequest request) {
        UUID id = service.start(request, TenantContext.current());
        String statusUrl = "/api/gap-analysis/" + id;
        return ResponseEntity.accepted().location(URI.create(statusUrl)).body(Map.of("reportId", id, "statusUrl", statusUrl));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','COMPLIANCE_OFFICER','AUDITOR')")
    public List<Map<String, Object>> list() {
        return reports.list(TenantContext.current());
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','COMPLIANCE_OFFICER','AUDITOR')")
    public Map<String, Object> get(@PathVariable UUID id) {
        return report(id);
    }

    @GetMapping("/{id}/findings")
    @PreAuthorize("hasAnyRole('ADMIN','COMPLIANCE_OFFICER','AUDITOR')")
    public List<Finding> findings(@PathVariable UUID id,
                                  @RequestParam(required = false) String status,
                                  @RequestParam(required = false) String severity) {
        report(id);
        return reports.findings(id, upper(status), upper(severity));
    }

    @GetMapping("/{id}/export")
    @PreAuthorize("hasAnyRole('ADMIN','COMPLIANCE_OFFICER','AUDITOR')")
    public ResponseEntity<byte[]> export(@PathVariable UUID id, @RequestParam(defaultValue = "pdf") String format) throws IOException {
        Map<String, Object> report = report(id);
        if (!"COMPLETED".equals(report.get("status"))) {
            throw ApiException.conflict("Report is not completed yet (status " + report.get("status") + ")");
        }
        List<Finding> findings = reports.findings(id, null, null);
        String base = "gap-report-" + id;
        return switch (format.toLowerCase()) {
            case "pdf" -> file(exporter.pdf(report, findings), base + ".pdf", MediaType.APPLICATION_PDF);
            case "docx" -> file(exporter.docx(report, findings), base + ".docx",
                    MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.wordprocessingml.document"));
            case "csv" -> file(exporter.csv(findings), base + ".csv", new MediaType("text", "csv"));
            case "json" -> file(exporter.json(report, findings), base + ".json", MediaType.APPLICATION_JSON);
            default -> throw ApiException.badRequest("format must be pdf, docx, csv or json");
        };
    }

    private Map<String, Object> report(UUID id) {
        return reports.find(id, TenantContext.current()).orElseThrow(() -> ApiException.notFound("Gap report"));
    }

    private static ResponseEntity<byte[]> file(byte[] body, String name, MediaType type) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + name + "\"")
                .contentType(type)
                .body(body);
    }

    private static String upper(String s) {
        return s == null || s.isBlank() ? null : s.strip().toUpperCase();
    }
}
