package com.edupulse.auth.domain;

import java.util.Set;
import java.util.UUID;

/** A user joined with its org unit and organization (what goes into the JWT). */
public record UserAccount(
        UUID id,
        String email,
        String passwordHash,
        String fullName,
        UUID orgUnitId,
        String orgUnitCode,
        String orgUnitName,
        UUID organizationId,
        String organizationName,
        int clearanceLevel,
        boolean enabled,
        Set<String> roles) {
}
