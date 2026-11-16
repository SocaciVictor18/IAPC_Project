package com.edupulse.compliance.audit;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class HashChainTest {

    @Test
    void anyFieldChangeChangesTheHash() {
        UUID id = UUID.randomUUID();
        UUID org = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        Instant now = Instant.parse("2026-11-16T10:00:00.123456Z");
        String h1 = AuditService.sha256(AuditService.canonical(null, id, org, user, "q", "a", "ANSWERED", now, List.of("c1")));
        String h2 = AuditService.sha256(AuditService.canonical(null, id, org, user, "q", "a!", "ANSWERED", now, List.of("c1")));
        String h3 = AuditService.sha256(AuditService.canonical(h1, id, org, user, "q", "a", "ANSWERED", now, List.of("c1")));
        assertThat(h1).hasSize(64).isNotEqualTo(h2).isNotEqualTo(h3);
        assertThat(AuditService.sha256(AuditService.canonical(null, id, org, user, "q", "a", "ANSWERED", now, List.of("c1"))))
                .isEqualTo(h1);
    }
}
