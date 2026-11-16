package com.edupulse.compliance.rag;

/**
 * Keys placed in the advisor context ({@code .advisors(a -> a.param(...))}). Spring AI copies the
 * request context into {@code Query.context()}, and both {@code MultiQueryExpander} and
 * {@code CompressionQueryTransformer} preserve it ({@code query.mutate()}), so every retrieval
 * thread sees the caller's tenant without ThreadLocals.
 */
public final class RagContextKeys {

    private RagContextKeys() {
    }

    /** {@link com.edupulse.common.security.TenantContext} – mandatory; retrieval fails closed without it. */
    public static final String TENANT = "edupulse_tenant";
    /** {@link RequestFilters} – optional, whitelisted extra filters from the client. */
    public static final String FILTERS = "edupulse_filters";
    /** {@link RagMode} */
    public static final String MODE = "edupulse_mode";
    /** {@link RagTrace} – mutable per-request trace recorded into the audit log. */
    public static final String TRACE = "edupulse_trace";
}
