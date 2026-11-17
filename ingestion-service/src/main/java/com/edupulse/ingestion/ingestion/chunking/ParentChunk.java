package com.edupulse.ingestion.ingestion.chunking;

import java.util.UUID;

/** A ~1000-token section kept inside one page, supplied to the LLM as context. */
public record ParentChunk(UUID id, int index, Integer pageNumber, String sectionRef, String text, int approxTokens) {
}
