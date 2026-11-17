package com.edupulse.compliance.qa;

import com.edupulse.compliance.audit.Outcome;

import java.util.List;
import java.util.UUID;

public record AskResponse(
        UUID conversationId,
        UUID auditId,
        String answer,
        Outcome outcome,
        List<Citation> citations,
        List<String> expandedQueries,
        long latencyMs) {

    /** Strict source citation: document name, page and the exact matching text snippet. */
    public record Citation(String label, String documentName, Integer page, String section, String snippet,
                           Double rerankScore, String match) {
    }
}
