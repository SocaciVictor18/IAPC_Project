package com.edupulse.auth.seed;

import com.edupulse.auth.repository.UserRepository;
import com.edupulse.common.security.Roles;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Demo data: one hospital with two organizational units and one user per role.
 * Clinical Operations and Billing are separate tenants, which shows the data isolation.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataSeeder implements ApplicationRunner {

    public static final UUID HOSPITAL = UUID.fromString("11111111-1111-1111-1111-111111111111");
    public static final UUID OU_CLINICAL = UUID.fromString("11111111-1111-1111-1111-0000000000a1");
    public static final UUID OU_BILLING = UUID.fromString("11111111-1111-1111-1111-0000000000b2");

    private final JdbcClient jdbc;
    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;

    @Value("${edupulse.seed.default-password}")
    private String password;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        jdbc.sql("insert into auth.organization (id, name) values (?, ?) on conflict (id) do nothing")
                .params(HOSPITAL, "Helix Health Hospital").update();
        orgUnit(OU_CLINICAL, "CLIN", "Clinical Operations");
        orgUnit(OU_BILLING, "BILL", "Billing & Insurance");

        user("admin@helix.test", "Ana Admin", OU_CLINICAL, 3, Roles.ADMIN);
        user("officer@helix.test", "Oana Officer", OU_CLINICAL, 3, Roles.COMPLIANCE_OFFICER);
        user("auditor@helix.test", "Andrei Auditor", OU_CLINICAL, 2, Roles.AUDITOR);
        user("doctor@helix.test", "Dan Doctor", OU_CLINICAL, 1, Roles.EMPLOYEE);
        user("officer.billing@helix.test", "Bianca Billing", OU_BILLING, 3, Roles.COMPLIANCE_OFFICER);
        user("clerk.billing@helix.test", "Cristi Clerk", OU_BILLING, 1, Roles.EMPLOYEE);
        log.info("Demo data ready: Helix Health Hospital, 2 org units, 6 users");
    }

    private void orgUnit(UUID id, String code, String name) {
        jdbc.sql("insert into auth.org_unit (id, organization_id, code, name) values (?, ?, ?, ?) on conflict (id) do nothing")
                .params(id, HOSPITAL, code, name).update();
    }

    private void user(String email, String fullName, UUID orgUnit, int clearance, String role) {
        if (users.findByEmail(email).isEmpty()) {
            users.create(email, passwordEncoder.encode(password), fullName, orgUnit, clearance, role);
        }
    }
}
