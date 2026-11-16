package com.edupulse.common.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class JwtAuthConverterTest {

    @Test
    void mapsRolesScopesAndTenant() {
        UUID user = UUID.randomUUID();
        UUID org = UUID.randomUUID();
        UUID ou = UUID.randomUUID();
        Jwt jwt = Jwt.withTokenValue("t")
                .header("alg", "RS256")
                .subject(user.toString())
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .claim(JwtClaimNames.ROLES, List.of("ROLE_ADMIN", "AUDITOR"))
                .claim(JwtClaimNames.SCOPE, "internal")
                .claim(JwtClaimNames.ORGANIZATION_ID, org.toString())
                .claim(JwtClaimNames.ORG_UNIT_ID, ou.toString())
                .claim(JwtClaimNames.CLEARANCE, 2)
                .claim(JwtClaimNames.EMAIL, "a@b.test")
                .build();

        var token = new JwtAuthConverter().convert(jwt);
        assertThat(token.getAuthorities()).extracting(GrantedAuthority::getAuthority)
                .containsExactlyInAnyOrder("ROLE_ADMIN", "ROLE_AUDITOR", "SCOPE_internal");

        TenantContext tenant = TenantContext.fromJwt(jwt);
        assertThat(tenant.organizationId()).isEqualTo(org);
        assertThat(tenant.orgUnitId()).isEqualTo(ou);
        assertThat(tenant.clearanceLevel()).isEqualTo(2);
        assertThat(tenant.organizationWide()).isTrue();
    }
}
