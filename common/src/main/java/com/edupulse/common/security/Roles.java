package com.edupulse.common.security;

/** Application roles (stored with the {@code ROLE_} prefix, as Spring Security expects). */
public final class Roles {

    private Roles() {
    }

    public static final String ADMIN = "ROLE_ADMIN";
    public static final String COMPLIANCE_OFFICER = "ROLE_COMPLIANCE_OFFICER";
    public static final String AUDITOR = "ROLE_AUDITOR";
    public static final String EMPLOYEE = "ROLE_EMPLOYEE";
}
