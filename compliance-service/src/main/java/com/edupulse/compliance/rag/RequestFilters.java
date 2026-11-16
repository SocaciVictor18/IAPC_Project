package com.edupulse.compliance.rag;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The only filters a client may add. They are validated here and AND-ed on top of the mandatory
 * tenant filter – the course's raw {@code String filter} parameter is deliberately NOT exposed,
 * because {@code "... || org_unit_id == 'other'"} would bypass tenant isolation.
 */
public record RequestFilters(String category, String docType, LocalDate uploadedAfter, List<UUID> documentIds) {

    private static final Set<String> CATEGORIES = Set.of("INTERNAL_POLICY", "REGULATION", "AUDIT_REPORT");
    private static final Pattern DOC_TYPE = Pattern.compile("[\\p{L}0-9 _.-]{1,64}");

    public static final RequestFilters NONE = new RequestFilters(null, null, null, null);

    public RequestFilters {
        if (category != null && !CATEGORIES.contains(category)) {
            throw new IllegalArgumentException("filters.category must be one of " + CATEGORIES);
        }
        if (docType != null && !DOC_TYPE.matcher(docType).matches()) {
            throw new IllegalArgumentException("filters.docType contains invalid characters");
        }
        if (documentIds != null && documentIds.size() > 50) {
            throw new IllegalArgumentException("filters.documentIds accepts at most 50 ids");
        }
        documentIds = documentIds == null || documentIds.isEmpty() ? null : List.copyOf(documentIds);
    }

    public static RequestFilters orNone(RequestFilters f) {
        return f == null ? NONE : f;
    }

    public RequestFilters withCategory(String c) {
        return new RequestFilters(c, docType, uploadedAfter, documentIds);
    }
}
