package com.edupulse.compliance.rag;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * Optional filters the user may add (category, document type). They are validated and always
 * combined with AND on top of the tenant filter taken from the JWT – unlike the course's free-text
 * {@code filter} parameter, a user can never widen the search to another org unit.
 */
public record RequestFilters(String category, String docType) {

    private static final Set<String> CATEGORIES = Set.of("INTERNAL_POLICY", "REGULATION", "AUDIT_REPORT");
    private static final Pattern DOC_TYPE = Pattern.compile("[\\p{L}0-9 _.-]{1,64}");

    public static final RequestFilters NONE = new RequestFilters(null, null);

    public RequestFilters {
        if (category != null && !CATEGORIES.contains(category)) {
            throw new IllegalArgumentException("filters.category must be one of " + CATEGORIES);
        }
        if (docType != null && !DOC_TYPE.matcher(docType).matches()) {
            throw new IllegalArgumentException("filters.docType contains invalid characters");
        }
    }

    public static RequestFilters orNone(RequestFilters f) {
        return f == null ? NONE : f;
    }
}
