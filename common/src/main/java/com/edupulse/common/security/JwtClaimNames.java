package com.edupulse.common.security;

/** Custom claims placed in EduPulse access tokens by the auth service. */
public final class JwtClaimNames {

    private JwtClaimNames() {
    }

    public static final String ISSUER = "edupulse-auth";

    public static final String EMAIL = "email";
    public static final String ORGANIZATION_ID = "org";
    public static final String ORGANIZATION_NAME = "orgName";
    public static final String ORG_UNIT_ID = "ou";
    public static final String CLEARANCE = "clr";
    public static final String ROLES = "roles";
}
