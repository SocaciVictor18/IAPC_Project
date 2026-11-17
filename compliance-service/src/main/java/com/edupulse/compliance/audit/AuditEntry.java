package com.edupulse.compliance.audit;

import java.util.List;
import java.util.UUID;

/** One row of the audit log: the question, the answer and the exact context chunks used. */
public record AuditEntry(
        UUID conversationId,
        UUID userId,
        String userEmail,
        UUID organizationId,
        UUID orgUnitId,
        String roles,
        String question,
        String transformedQuery,
        List<String> expandedQueries,
        String appliedFilter,
        String answer,
        int latencyMs,
        Outcome outcome,
        List<Chunk> chunks) {

    public record Chunk(int rank, String label, UUID chunkId, UUID documentId, String source, Integer pageNumber,
                        Double rerankScore, boolean cited, String snippet) {
    }
}
