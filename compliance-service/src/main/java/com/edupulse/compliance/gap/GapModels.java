package com.edupulse.compliance.gap;

import java.util.List;
import java.util.UUID;

/** Data types of the gap analysis. */
public final class GapModels {

    private GapModels() {
    }

    /** A requirement extracted from the regulation (step 1). */
    public record Requirement(String ref, String text, Integer page) {
    }

    /** What the LLM decided for one requirement (step 3). {@code evidence} = labels like "S1" of the policy passages used. */
    public record Judgement(String status, String severity, String rationale, String recommendation, List<String> evidence) {
    }

    /** A policy passage proving the decision: document, page and snippet. */
    public record Evidence(String source, Integer page, String snippet) {
    }

    /** One line of the final report. status: COVERED | PARTIAL | GAP. */
    public record Finding(UUID id, int ordinal, String requirementRef, String requirementText, Integer requirementPage,
                          String status, String severity, String rationale, String recommendation, List<Evidence> evidence) {
    }

    public record StartRequest(UUID regulationDocumentId) {
    }
}
