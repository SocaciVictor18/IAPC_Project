package com.edupulse.compliance.qa;

import com.edupulse.compliance.rag.RagMode;
import com.edupulse.compliance.rag.RequestFilters;
import jakarta.validation.constraints.NotBlank;

import java.util.UUID;

/**
 * @param conversationId continue an existing conversation (multi-turn); null starts a new one
 * @param filters        optional whitelisted filters (category, docType, uploadedAfter, documentIds)
 * @param mode           FULL (default) or FAST
 */
public record AskRequest(@NotBlank String question, UUID conversationId, RequestFilters filters, RagMode mode) {
}
