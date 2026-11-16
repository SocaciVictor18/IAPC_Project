package com.edupulse.compliance.rag;

import com.edupulse.common.MetadataKeys;
import com.edupulse.common.security.TenantContext;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder.Op;
import org.springframework.stereotype.Component;

import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Builds the tenant isolation filter (FR "Multi-Tenant Data Isolation") in two equivalent forms:
 * a Spring AI {@link Filter.Expression} for the pgvector search, and a parameterised SQL clause for
 * the full-text search. Values come only from the validated JWT and the whitelisted {@link RequestFilters}.
 */
@Component
public class TenantFilterProvider {

    private static final String MD = "metadata::jsonb ->> ";

    public record SqlFilter(String clause, List<Object> params) {
    }

    public static TenantContext requireTenant(Map<String, Object> context) {
        Object t = context == null ? null : context.get(RagContextKeys.TENANT);
        if (t instanceof TenantContext tenant) {
            return tenant;
        }
        // Fail closed: never search without a tenant.
        throw new IllegalStateException("Retrieval attempted without a tenant context");
    }

    public static RequestFilters filters(Map<String, Object> context) {
        Object f = context == null ? null : context.get(RagContextKeys.FILTERS);
        return f instanceof RequestFilters rf ? rf : RequestFilters.NONE;
    }

    public Filter.Expression vectorFilter(TenantContext t, RequestFilters f) {
        FilterExpressionBuilder b = new FilterExpressionBuilder();
        Op op = b.eq(MetadataKeys.ORGANIZATION_ID, t.organizationId().toString());
        if (!t.organizationWide()) {
            op = b.and(op, b.eq(MetadataKeys.ORG_UNIT_ID, t.orgUnitId().toString()));
        }
        op = b.and(op, b.lte(MetadataKeys.CLEARANCE_LEVEL, t.clearanceLevel()));
        if (f.category() != null) {
            op = b.and(op, b.eq(MetadataKeys.CATEGORY, f.category()));
        }
        if (f.docType() != null) {
            op = b.and(op, b.eq(MetadataKeys.DOC_TYPE, f.docType()));
        }
        if (f.uploadedAfter() != null) {
            op = b.and(op, b.gte(MetadataKeys.UPLOADED_AT, epochMillis(f)));
        }
        if (f.documentIds() != null) {
            op = b.and(op, b.in(MetadataKeys.DOCUMENT_ID, f.documentIds().stream().map(Object::toString).map(s -> (Object) s).toList()));
        }
        return op.build();
    }

    public SqlFilter sqlFilter(TenantContext t, RequestFilters f) {
        StringBuilder sql = new StringBuilder("(" + MD + "'" + MetadataKeys.ORGANIZATION_ID + "') = ?");
        List<Object> params = new ArrayList<>();
        params.add(t.organizationId().toString());
        if (!t.organizationWide()) {
            sql.append(" and (" + MD + "'" + MetadataKeys.ORG_UNIT_ID + "') = ?");
            params.add(t.orgUnitId().toString());
        }
        sql.append(" and (" + MD + "'" + MetadataKeys.CLEARANCE_LEVEL + "')::int <= ?");
        params.add(t.clearanceLevel());
        if (f.category() != null) {
            sql.append(" and (" + MD + "'" + MetadataKeys.CATEGORY + "') = ?");
            params.add(f.category());
        }
        if (f.docType() != null) {
            sql.append(" and (" + MD + "'" + MetadataKeys.DOC_TYPE + "') = ?");
            params.add(f.docType());
        }
        if (f.uploadedAfter() != null) {
            sql.append(" and (" + MD + "'" + MetadataKeys.UPLOADED_AT + "')::bigint >= ?");
            params.add(epochMillis(f));
        }
        if (f.documentIds() != null) {
            sql.append(" and (" + MD + "'" + MetadataKeys.DOCUMENT_ID + "') = any(?)");
            params.add(f.documentIds().stream().map(Object::toString).toArray(String[]::new));
        }
        return new SqlFilter(sql.toString(), params);
    }

    private static long epochMillis(RequestFilters f) {
        return f.uploadedAfter().atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli();
    }
}
