package com.edupulse.ingestion.ingestion.reader;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class TextNormalizerTest {

    @Test
    void removesRunningHeadersPageNumbersAndHyphenation() {
        List<String> pages = List.of(
                "EAAlgorithm Internal Security Handbook\nv1.0\nPasswords must be 12 characters.\n1",
                "EAAlgorithm Internal Security Handbook\nv1.0\nAll data handled by EAAl-\ngorithm systems.\n2",
                "EAAlgorithm Internal Security Handbook\nv1.0\nQuarterly review.\n3");
        Set<String> repeated = TextNormalizer.detectRepeatedLines(pages);
        assertThat(repeated).contains("EAAlgorithm Internal Security Handbook", "v1.0");

        String cleaned = TextNormalizer.normalize(pages.get(1), repeated);
        assertThat(cleaned).isEqualTo("All data handled by EAAlgorithm systems.");
    }

    @Test
    void normalisesBulletsAndSpaces() {
        assertThat(TextNormalizer.normalize("�  MFA   is mandatory", Set.of())).isEqualTo("• MFA is mandatory");
    }
}
