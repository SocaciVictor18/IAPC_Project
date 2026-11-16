package com.edupulse.common.security;

import java.util.Set;

/** Application roles (stored with the {@code ROLE_} prefix, as Spring Security expects). */
public final class Roles {

    private Roles() {
    }

    public static final String ADMIN = "ROLE_ADMIN";
    public static final String COMPLIANCE_OFFICER = "ROLE_COMPLIANCE_OFFICER";
    public static final String AUDITOR = "ROLE_AUDITOR";
    public static final String EMPLOYEE = "ROLE_EMPLOYEE";
    /** Machine-to-machine token issued with the client-credentials flow. */
    public static final String SERVICE = "ROLE_SERVICE";

    public static final Set<String> USER_ROLES = Set.of(ADMIN, COMPLIANCE_OFFICER, AUDITOR, EMPLOYEE);

    /** Scope carried by service tokens; endpoints under {@code /internal/**} require it. */
    public static final String SCOPE_INTERNAL = "internal";
}
