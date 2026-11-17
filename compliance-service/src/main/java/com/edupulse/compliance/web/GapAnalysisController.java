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

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Start: ADMIN, COMPLIANCE_OFFICER. Read / export: also AUDITOR. */
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
        UUID id = service.start(request.regulationDocumentId(), TenantContext.current());
        return ResponseEntity.accepted().body(Map.of("reportId", id));
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
    public List<Finding> findings(@PathVariable UUID id) {
        report(id);
        return reports.findings(id);
    }

    @GetMapping("/{id}/export")
    @PreAuthorize("hasAnyRole('ADMIN','COMPLIANCE_OFFICER','AUDITOR')")
    public ResponseEntity<byte[]> export(@PathVariable UUID id, @RequestParam(defaultValue = "pdf") String format) {
        Map<String, Object> report = report(id);
        List<Finding> findings = reports.findings(id);
        boolean pdf = !"json".equalsIgnoreCase(format);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"gap-report-" + id + (pdf ? ".pdf\"" : ".json\""))
                .contentType(pdf ? MediaType.APPLICATION_PDF : MediaType.APPLICATION_JSON)
                .body(pdf ? exporter.pdf(report, findings) : exporter.json(report, findings));
    }

    private Map<String, Object> report(UUID id) {
        return reports.find(id, TenantContext.current()).orElseThrow(() -> ApiException.notFound("Gap report"));
    }
}
