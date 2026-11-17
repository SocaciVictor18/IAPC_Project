package com.edupulse.compliance.gap;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Small local models (llama3.2) wrap JSON in text or markdown – the gap analysis must still work. */
class GapParsingTest {

    @Test
    void judgementIsReadFromJsonInsideText() {
        var j = GapAnalysisService.parseJudgement("""
                Sure! Here is my assessment:
                ```json
                {"Status": "Partially covered", "severity": "high", "rationale": "Only 'legal timelines'.", "evidence": ["S1", "[S2]"]}
                ```
                """);
        assertThat(GapAnalysisService.normalizeStatus(j.status())).isEqualTo("PARTIAL");
        assertThat(j.severity()).isEqualTo("HIGH");
        assertThat(j.evidence()).containsExactly("S1", "S2");
    }

    @Test
    void statusIsNormalised() {
        assertThat(GapAnalysisService.normalizeStatus("COVERED")).isEqualTo("COVERED");
        assertThat(GapAnalysisService.normalizeStatus("Not covered")).isEqualTo("GAP");
        assertThat(GapAnalysisService.normalizeStatus("???")).isEqualTo("GAP");
    }

    @Test
    void requirementsAreReadFromObjectOrArray() {
        assertThat(GapAnalysisService.requirementsArray("[{\"ref\":\"1.1\",\"text\":\"MFA MUST be used\"}]")).hasSize(1);
        assertThat(GapAnalysisService.requirementsArray("Result: {\"requirements\":[{\"text\":\"a\"},{\"text\":\"b\"}]}")).hasSize(2);
        assertThat(GapAnalysisService.requirementsArray("no json")).isEmpty();
    }
}
