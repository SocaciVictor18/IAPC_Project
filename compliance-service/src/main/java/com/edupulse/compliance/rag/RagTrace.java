package com.edupulse.compliance.rag;

import java.util.List;

/** Records what the RAG pipeline did for one question (stored in the audit log and returned to the client). */
public class RagTrace {

    private volatile String transformedQuery;
    private volatile List<String> expandedQueries = List.of();
    private volatile String appliedFilter;
    private volatile boolean outOfScope;

    public String transformedQuery() {
        return transformedQuery;
    }

    public void transformedQuery(String q) {
        this.transformedQuery = q;
    }

    public List<String> expandedQueries() {
        return expandedQueries;
    }

    public void expandedQueries(List<String> queries) {
        this.expandedQueries = List.copyOf(queries);
    }

    public String appliedFilter() {
        return appliedFilter;
    }

    public void appliedFilter(String filter) {
        this.appliedFilter = filter;
    }

    public boolean outOfScope() {
        return outOfScope;
    }

    public void markOutOfScope() {
        this.outOfScope = true;
    }
}
