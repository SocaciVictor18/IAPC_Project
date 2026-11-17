package com.edupulse.compliance.rag;

/**
 * Keys passed with {@code .advisors(a -> a.param(KEY, value))}. Spring AI copies them into
 * {@code Query.context()}, so the retriever knows which user is asking (tenant filter).
 */
public final class RagContextKeys {

    private RagContextKeys() {
    }

    /** {@link com.edupulse.common.security.TenantContext} of the user – required for every search. */
    public static final String TENANT = "edupulse_tenant";
    /** {@link RequestFilters} – optional extra filters chosen by the user. */
    public static final String FILTERS = "edupulse_filters";
    /** {@link RagTrace} – what the pipeline did, saved in the audit log. */
    public static final String TRACE = "edupulse_trace";
}
