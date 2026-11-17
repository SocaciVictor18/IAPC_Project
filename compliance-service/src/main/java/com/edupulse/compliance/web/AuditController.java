package com.edupulse.compliance.web;

import com.edupulse.common.security.TenantContext;
import com.edupulse.common.web.ApiException;
import com.edupulse.compliance.audit.AuditService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
public class AuditController {

    private final AuditService audit;

    /** The user's own conversations. */
    @GetMapping("/api/conversations")
    public List<Map<String, Object>> conversations() {
        return audit.conversations(TenantContext.current());
    }

    @GetMapping("/api/conversations/{id}/messages")
    public List<Map<String, Object>> messages(@PathVariable UUID id) {
        return audit.messages(id, TenantContext.current());
    }

    /** Audit log (visibility depends on the role, see AuditService.search). */
    @GetMapping("/api/audit")
    public List<Map<String, Object>> search() {
        return audit.search(TenantContext.current(), 200);
    }

    /** One audit record with the context chunks that were used. */
    @GetMapping("/api/audit/{id}")
    public Map<String, Object> detail(@PathVariable UUID id) {
        return audit.detail(id, TenantContext.current()).orElseThrow(() -> ApiException.notFound("Audit record"));
    }
}
