package com.edupulse.compliance.rag;

import com.edupulse.compliance.gap.GapParsingAccess;
import com.edupulse.compliance.rag.postprocessing.SnippetAccess;
import com.edupulse.compliance.rag.preprocessing.ExpanderAccess;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Robustness against the output style of small local models (prose, numbering, markdown fences). */
class LenientParsingTest {

    @Test
    void expansionVariantsAreCleanedFromNumberedOutput() {
        String output = """
                Here are 3 paraphrases:
                1. What are the multi factor authentication rules?
                2) "When is MFA mandatory for employees?"
                - How do I enable two-step login
                4. extra variant beyond the limit
                """;
        List<String> v = ExpanderAccess.parse(output, "how should I handle multi factor authentication", 3);
        assertThat(v).containsExactly("What are the multi factor authentication rules?",
                "When is MFA mandatory for employees?", "How do I enable two-step login");
    }

    @Test
    void judgementIsParsedFromProseWrappedJson() {
        var j = GapParsingAccess.judgement("""
                Sure! Here is my assessment:
                ```json
                {"Status": "Partially covered", "severity": "high", "rationale": "Only 'legal timelines'.", "evidence": "S1, S2"}
                ```
                """);
        assertThat(GapParsingAccess.status(j.status())).isEqualTo("PARTIAL");
        assertThat(j.evidence()).containsExactly("S1", "S2");
        assertThat(j.rationale()).contains("legal timelines");
    }

    @Test
    void statusNormalisation() {
        assertThat(GapParsingAccess.status("COVERED")).isEqualTo("COVERED");
        assertThat(GapParsingAccess.status("Not covered")).isEqualTo("GAP");
        assertThat(GapParsingAccess.status("N/A")).isEqualTo("NOT_APPLICABLE");
        assertThat(GapParsingAccess.status("???")).isEqualTo("GAP");
    }

    @Test
    void extractionAcceptsArrayOrObject() {
        assertThat(GapParsingAccess.extraction("[{\"ref\":\"1.1\",\"text\":\"MFA MUST be enforced\",\"obligationType\":\"MUST\"}]").requirements())
                .hasSize(1);
        assertThat(GapParsingAccess.extraction("Result: {\"requirements\":[{\"text\":\"a\"},{\"requirement\":\"b\"}]}").requirements())
                .extracting(r -> r.text()).containsExactly("a", "b");
        assertThatThrownBy(() -> GapParsingAccess.extraction("no json here")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void snippetPrefersTheSentenceMatchingTheQuestion() {
        String child = "EAAlgorithm Internal Security Handbook Version 1.0 Confidential - Internal Use Only. "
                + "1. Password & Authentication Requirements. Strong authentication practices are critical to preventing "
                + "unauthorized access across all company systems and cloud platforms used by employees and contractors. "
                + "Multi-Factor Authentication (MFA) is mandatory for all accounts accessing company systems. "
                + "Passwords must be a minimum of 12 characters.";
        String s = SnippetAccess.best(child, "how should I handle multi factor authentication", 300);
        assertThat(s).startsWith("Multi-Factor Authentication (MFA) is mandatory");
        assertThat(s.length()).isLessThanOrEqualTo(301);
    }
}
