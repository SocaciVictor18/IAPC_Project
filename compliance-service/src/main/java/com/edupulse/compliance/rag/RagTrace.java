package com.edupulse.compliance.rag;

import com.edupulse.compliance.audit.Outcome;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Per-request, thread-safe record of what the pipeline did (written to the audit log and shown in
 * the API response for transparency). Shared by reference through the advisor context.
 */
public class RagTrace {

    private volatile String transformedQuery;
    private final List<String> expandedQueries = new CopyOnWriteArrayList<>();
    private volatile String appliedFilter;
    private volatile Outcome forcedOutcome;
    private volatile String outcomeReason;
    private volatile int candidates;
    private final Map<String, Long> stepMillis = new ConcurrentHashMap<>();

    public String transformedQuery() {
        return transformedQuery;
    }

    public void transformedQuery(String q) {
        this.transformedQuery = q;
    }

    public List<String> expandedQueries() {
        return List.copyOf(expandedQueries);
    }

    public void expandedQueries(List<String> queries) {
        expandedQueries.clear();
        expandedQueries.addAll(queries);
    }

    public String appliedFilter() {
        return appliedFilter;
    }

    public void appliedFilter(String f) {
        this.appliedFilter = f;
    }

    public Outcome forcedOutcome() {
        return forcedOutcome;
    }

    public String outcomeReason() {
        return outcomeReason;
    }

    public void force(Outcome outcome, String reason) {
        this.forcedOutcome = outcome;
        this.outcomeReason = reason;
    }

    public int candidates() {
        return candidates;
    }

    public void candidates(int n) {
        this.candidates = n;
    }

    public void step(String name, long millis) {
        stepMillis.merge(name, millis, Long::sum);
    }

    public Map<String, Long> steps() {
        return Map.copyOf(stepMillis);
    }
}
