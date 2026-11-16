package com.edupulse.compliance.gap;

import java.util.List;
import java.util.UUID;

/** Structured-output types of the gap-analysis agent and its persisted views. */
public final class GapModels {

    private GapModels() {
    }

    /** Output of the EXTRACT step (one LLM call per regulation section). */
    public record ExtractedRequirement(String ref, String text, String obligationType) {
    }

    public record Extraction(List<ExtractedRequirement> requirements) {
    }

    /** Output of the JUDGE step. {@code evidence} holds the [S#] labels of the policy passages used. */
    public record Judgement(String status, String severity, String rationale, String recommendation, List<String> evidence) {
    }

    public record Evidence(String label, String documentId, String source, Integer page, String section, String snippet) {
    }

    public record Requirement(String ref, String text, Integer page, String obligationType) {
    }

    public record Finding(UUID id, int ordinal, String requirementRef, String requirementText, Integer requirementPage,
                          String obligationType, String status, String severity, String rationale, String recommendation,
                          List<Evidence> evidence) {
    }

    public record Scope(List<String> docTypes) {
    }

    public record StartRequest(UUID regulationDocumentId, Scope scope) {
    }
}
