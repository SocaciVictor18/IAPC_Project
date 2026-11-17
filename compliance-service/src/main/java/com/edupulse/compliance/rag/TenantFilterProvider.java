package com.edupulse.compliance.rag;

import com.edupulse.common.MetadataKeys;
import com.edupulse.common.security.TenantContext;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder.Op;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Multi-tenant data isolation: the user only searches documents of their organization, their
 * org unit (admins: whole organization) and up to their clearance level. Built in two forms:
 * a Spring AI {@link Filter.Expression} for pgvector and a parameterised SQL condition for full-text search.
 */
@Component
public class TenantFilterProvider {

    public record SqlFilter(String clause, List<Object> params) {
    }

    /** Refuses to search without a tenant ("fail closed"). */
    public static TenantContext requireTenant(Map<String, Object> context) {
        if (context != null && context.get(RagContextKeys.TENANT) instanceof TenantContext tenant) {
            return tenant;
        }
        throw new IllegalStateException("Retrieval attempted without a tenant context");
    }

    public static RequestFilters filters(Map<String, Object> context) {
        return context != null && context.get(RagContextKeys.FILTERS) instanceof RequestFilters f ? f : RequestFilters.NONE;
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
        return op.build();
    }

    public SqlFilter sqlFilter(TenantContext t, RequestFilters f) {
        StringBuilder sql = new StringBuilder("metadata::jsonb ->> 'organization_id' = ?");
        List<Object> params = new ArrayList<>(List.of(t.organizationId().toString()));
        if (!t.organizationWide()) {
            sql.append(" and metadata::jsonb ->> 'org_unit_id' = ?");
            params.add(t.orgUnitId().toString());
        }
        sql.append(" and (metadata::jsonb ->> 'clearance_level')::int <= ?");
        params.add(t.clearanceLevel());
        if (f.category() != null) {
            sql.append(" and metadata::jsonb ->> 'category' = ?");
            params.add(f.category());
        }
        if (f.docType() != null) {
            sql.append(" and metadata::jsonb ->> 'doc_type' = ?");
            params.add(f.docType());
        }
        return new SqlFilter(sql.toString(), params);
    }
}
