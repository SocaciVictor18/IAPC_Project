package com.edupulse.compliance.qa;

import com.edupulse.compliance.audit.Outcome;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record AskResponse(
        UUID conversationId,
        UUID auditId,
        String answer,
        Outcome outcome,
        boolean ungrounded,
        List<Citation> citations,
        Usage usage,
        long latencyMs,
        Retrieval retrieval) {

    /**
     * Built in code from the documents actually placed in the LLM context (ADR-005), not parsed from
     * the generated text. {@code snippet} is the exact matched child paragraph.
     */
    public record Citation(String label, boolean cited, String documentId, String documentName, String title,
                           Integer page, String section, String snippet, Double retrievalScore, Double rerankScore,
                           String match) {
    }

    public record Usage(Integer promptTokens, Integer completionTokens, String model) {
    }

    public record Retrieval(String transformedQuery, List<String> expandedQueries, int candidates,
                            Map<String, Long> stepMillis) {
    }
}
