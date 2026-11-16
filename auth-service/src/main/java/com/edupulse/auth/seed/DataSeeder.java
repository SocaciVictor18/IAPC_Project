package com.edupulse.auth.seed;

import com.edupulse.auth.repository.UserRepository;
import com.edupulse.common.security.Roles;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;
import java.util.UUID;

/**
 * Demo seed: two organizations with two org units each and one user per role, so tenant
 * isolation (OU vs OU, org vs org) and RBAC can be demonstrated. Idempotent.
 * Fixed UUIDs make the Postman collection and the evaluation harness reproducible.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "edupulse.seed.enabled", havingValue = "true", matchIfMissing = true)
public class DataSeeder implements ApplicationRunner {

    public static final UUID ORG_ACME = UUID.fromString("11111111-1111-1111-1111-111111111111");
    public static final UUID OU_ACME_SECURITY = UUID.fromString("11111111-1111-1111-1111-0000000000a1");
    public static final UUID OU_ACME_FINANCE = UUID.fromString("11111111-1111-1111-1111-0000000000b2");
    public static final UUID ORG_HELIX = UUID.fromString("22222222-2222-2222-2222-222222222222");
    public static final UUID OU_HELIX_COMPLIANCE = UUID.fromString("22222222-2222-2222-2222-0000000000c1");
    public static final UUID OU_HELIX_RESEARCH = UUID.fromString("22222222-2222-2222-2222-0000000000d2");

    private final JdbcClient jdbc;
    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;

    @Value("${edupulse.seed.default-password}")
    private String defaultPassword;

    @Value("${edupulse.seed.service-client-id:compliance-service}")
    private String serviceClientId;

    @Value("${edupulse.seed.service-client-secret}")
    private String serviceClientSecret;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        org(ORG_ACME, "Acme Fintech", "Fintech");
        ou(OU_ACME_SECURITY, ORG_ACME, "SEC", "Information Security");
        ou(OU_ACME_FINANCE, ORG_ACME, "FIN", "Finance Operations");
        org(ORG_HELIX, "Helix Health", "Healthcare");
        ou(OU_HELIX_COMPLIANCE, ORG_HELIX, "CMP", "Regulatory Compliance");
        ou(OU_HELIX_RESEARCH, ORG_HELIX, "RES", "Clinical Research");

        user("admin@acme.test", "Alice Admin", OU_ACME_SECURITY, 3, Roles.ADMIN);
        user("officer@acme.test", "Oscar Officer", OU_ACME_SECURITY, 3, Roles.COMPLIANCE_OFFICER);
        user("auditor@acme.test", "Ada Auditor", OU_ACME_SECURITY, 2, Roles.AUDITOR);
        user("employee@acme.test", "Eve Employee", OU_ACME_SECURITY, 1, Roles.EMPLOYEE);
        user("officer.fin@acme.test", "Fiona Finance", OU_ACME_FINANCE, 3, Roles.COMPLIANCE_OFFICER);
        user("employee.fin@acme.test", "Frank Finance", OU_ACME_FINANCE, 1, Roles.EMPLOYEE);
        user("admin@helix.test", "Hank Helix", OU_HELIX_COMPLIANCE, 3, Roles.ADMIN);
        user("officer@helix.test", "Holly Helix", OU_HELIX_COMPLIANCE, 3, Roles.COMPLIANCE_OFFICER);

        jdbc.sql("""
                        insert into auth.service_client (client_id, secret_hash, scopes) values (?, ?, 'internal')
                        on conflict (client_id) do update set secret_hash = excluded.secret_hash
                        """)
                .params(serviceClientId, passwordEncoder.encode(serviceClientSecret))
                .update();
        log.info("Seed data ensured (2 organizations, 4 org units, 8 users, service client '{}')", serviceClientId);
    }

    private void org(UUID id, String name, String industry) {
        jdbc.sql("insert into auth.organization (id, name, industry) values (?, ?, ?) on conflict (id) do nothing")
                .params(id, name, industry).update();
    }

    private void ou(UUID id, UUID org, String code, String name) {
        jdbc.sql("insert into auth.org_unit (id, organization_id, code, name) values (?, ?, ?, ?) on conflict (id) do nothing")
                .params(id, org, code, name).update();
    }

    private void user(String email, String fullName, UUID ou, int clearance, String role) {
        if (users.findByEmail(email).isEmpty()) {
            users.create(email, passwordEncoder.encode(defaultPassword), fullName, ou, clearance, Set.of(role));
        }
    }
}
