package com.edupulse.compliance.web;

import com.edupulse.common.security.Roles;
import com.edupulse.common.security.TenantContext;
import com.edupulse.common.web.ApiException;
import com.edupulse.compliance.audit.AuditService;
import com.edupulse.compliance.audit.AuditService.AuditQuery;
import com.edupulse.compliance.audit.Outcome;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
public class AuditController {

    private final AuditService audit;

    // ---- conversation history (own conversations only)

    @GetMapping("/api/conversations")
    public List<Map<String, Object>> conversations() {
        return audit.conversations(TenantContext.current());
    }

    @GetMapping("/api/conversations/{id}/messages")
    public List<Map<String, Object>> messages(@PathVariable UUID id) {
        return audit.messages(id, TenantContext.current());
    }

    // ---- audit log (RBAC applied inside the service: employees only see their own queries)

    @GetMapping("/api/audit")
    public Map<String, Object> search(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
                                      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
                                      @RequestParam(required = false) UUID userId,
                                      @RequestParam(required = false) Outcome outcome,
                                      @RequestParam(defaultValue = "0") int page,
                                      @RequestParam(defaultValue = "50") int size) {
        return audit.search(new AuditQuery(from, to, userId, outcome, Math.max(0, page), Math.clamp(size, 1, 500)),
                TenantContext.current());
    }

    @GetMapping("/api/audit/{id}")
    public Map<String, Object> detail(@PathVariable UUID id) {
        return audit.detail(id, TenantContext.current()).orElseThrow(() -> ApiException.notFound("Audit record"));
    }

    @GetMapping("/api/audit/export")
    @PreAuthorize("hasAnyRole('ADMIN','COMPLIANCE_OFFICER','AUDITOR')")
    public ResponseEntity<String> export(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
                                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
                                         @RequestParam(required = false) Outcome outcome) {
        String csv = audit.exportCsv(new AuditQuery(from, to, null, outcome, 0, 10_000), TenantContext.current());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"audit-log.csv\"")
                .contentType(new MediaType("text", "csv"))
                .body(csv);
    }

    /** Recomputes the organization's hash chain (tamper evidence). */
    @GetMapping("/api/audit/verify")
    @PreAuthorize("hasAnyRole('ADMIN','AUDITOR')")
    public AuditService.ChainVerification verify() {
        TenantContext t = TenantContext.current();
        if (!t.hasAnyRole(Roles.ADMIN, Roles.AUDITOR)) {
            throw ApiException.forbidden("Not allowed");
        }
        return audit.verifyChain(t.organizationId());
    }
}
