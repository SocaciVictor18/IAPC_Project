package com.edupulse.compliance.audit;

import java.util.List;
import java.util.UUID;

/** Everything recorded for one query (input to {@link AuditService#record}). */
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
        String model,
        Integer promptTokens,
        Integer completionTokens,
        int latencyMs,
        Integer ttftMs,
        Outcome outcome,
        boolean ungrounded,
        boolean streamed,
        List<Chunk> chunks) {

    public record Chunk(int rank, String label, UUID chunkId, UUID parentId, UUID documentId, String source,
                        Integer pageNumber, String sectionRef, Double retrievalScore, Double rerankScore,
                        String match, boolean cited, String snippet) {
    }
}
