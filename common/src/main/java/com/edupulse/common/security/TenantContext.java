package com.edupulse.common.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Who is asking, and which slice of the data they may see. Built from the validated JWT only
 * (never from request parameters), so the tenant filter cannot be influenced by the client.
 * <p>
 * It is an immutable value: the RAG pipeline runs on other threads (multi-query fan-out),
 * so it is passed explicitly through the advisor context instead of a ThreadLocal.
 */
public record TenantContext(
        UUID userId,
        String email,
        UUID organizationId,
        String organizationName,
        UUID orgUnitId,
        int clearanceLevel,
        Set<String> roles) {

    public TenantContext {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(orgUnitId, "orgUnitId");
        roles = roles == null ? Set.of() : Set.copyOf(roles);
    }

    public boolean hasRole(String role) {
        return roles.contains(role);
    }

    public boolean hasAnyRole(String... candidates) {
        for (String c : candidates) {
            if (roles.contains(c)) {
                return true;
            }
        }
        return false;
    }

    /** Admins see every org unit of their organization; everybody else only their own unit. */
    public boolean organizationWide() {
        return hasRole(Roles.ADMIN);
    }

    public static TenantContext fromJwt(Jwt jwt) {
        List<String> roles = jwt.getClaimAsStringList(JwtClaimNames.ROLES);
        Object clr = jwt.getClaim(JwtClaimNames.CLEARANCE);
        int clearance = clr instanceof Number n ? n.intValue() : Integer.parseInt(String.valueOf(clr));
        return new TenantContext(
                UUID.fromString(jwt.getSubject()),
                jwt.getClaimAsString(JwtClaimNames.EMAIL),
                UUID.fromString(jwt.getClaimAsString(JwtClaimNames.ORGANIZATION_ID)),
                jwt.getClaimAsString(JwtClaimNames.ORGANIZATION_NAME),
                UUID.fromString(jwt.getClaimAsString(JwtClaimNames.ORG_UNIT_ID)),
                clearance,
                roles == null ? Set.of() : Set.copyOf(roles));
    }

    /** Tenant of the authenticated user on the current request thread. */
    public static TenantContext current() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken token) {
            return fromJwt(token.getToken());
        }
        throw new IllegalStateException("No authenticated JWT user on the current thread");
    }
}
