package com.edupulse.compliance.qa;

import com.edupulse.compliance.rag.RequestFilters;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * @param conversationId continue an existing conversation; null starts a new one
 * @param filters        optional: category and/or document type
 */
public record AskRequest(@NotBlank @Size(max = 2000) String question, UUID conversationId, RequestFilters filters) {
}
