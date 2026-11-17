package com.edupulse.compliance.rag;

import com.edupulse.common.MetadataKeys;
import com.edupulse.common.security.Roles;
import com.edupulse.common.security.TenantContext;
import com.edupulse.compliance.config.RagConfigData;
import com.edupulse.compliance.qa.CitationExtractor;
import com.edupulse.compliance.rag.postprocessing.CitationHeaderPostProcessor;
import com.edupulse.compliance.rag.preprocessing.DomainSynonymTransformer;
import com.edupulse.compliance.rag.retrieval.ReciprocalRankFusion;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RagComponentsTest {

    private static RagConfigData config() {
        LinkedHashMap<String, String> synonyms = new LinkedHashMap<>();
        synonyms.put("MFA", "multi factor authentication");
        synonyms.put("PHI", "protected health information");
        return new RagConfigData("m", 768, synonyms, null, null, null, null);
    }

    private static TenantContext tenant(String role) {
        return new TenantContext(UUID.randomUUID(), "u@x.test", UUID.fromString("11111111-1111-1111-1111-111111111111"),
                "Helix Health Hospital", UUID.fromString("11111111-1111-1111-1111-0000000000a1"), 2, Set.of(role));
    }

    @Test
    void synonymTransformerWorksLikeInTheCourse() {
        DomainSynonymTransformer t = new DomainSynonymTransformer(config());
        TenantContext tenant = tenant(Roles.EMPLOYEE);
        Query out = t.transform(Query.builder().text("how should I handle MFA?").context(Map.of(RagContextKeys.TENANT, tenant)).build());
        assertThat(out.text()).isEqualTo("how should I handle multi factor authentication");
        assertThat(out.context()).containsEntry(RagContextKeys.TENANT, tenant);   // the tenant is kept
        assertThat(t.transform(new Query("Please, can you tell me where PHI is stored?")).text())
                .isEqualTo("where protected health information is stored");
    }

    @Test
    void rrfPutsChunksFoundByBothSearchesFirst() {
        List<Document> fused = ReciprocalRankFusion.fuse(List.of(doc("a"), doc("b")), List.of(doc("c"), doc("b")), 10);
        assertThat(fused).extracting(Document::getId).containsExactly("b", "a", "c");
        assertThat(fused).extracting(d -> d.getMetadata().get(MetadataKeys.MATCH)).containsExactly("both", "semantic", "keyword");
    }

    @Test
    void tenantFilterAlwaysContainsOrgUnitAndClearance() {
        TenantFilterProvider p = new TenantFilterProvider();
        TenantContext doctor = tenant(Roles.EMPLOYEE);
        assertThat(p.vectorFilter(doctor, RequestFilters.NONE).toString())
                .contains(doctor.organizationId().toString()).contains(doctor.orgUnitId().toString()).contains("clearance_level");
        TenantFilterProvider.SqlFilter sql = p.sqlFilter(doctor, new RequestFilters("INTERNAL_POLICY", null));
        assertThat(sql.clause()).contains("org_unit_id").doesNotContain(doctor.orgUnitId().toString());  // values are bound
        assertThat(sql.params()).contains(doctor.orgUnitId().toString(), 2, "INTERNAL_POLICY");
        // admins see the whole organization
        assertThat(p.vectorFilter(tenant(Roles.ADMIN), RequestFilters.NONE).toString()).doesNotContain("org_unit_id");
    }

    @Test
    void userFiltersCannotBypassTheTenantFilter() {
        assertThatThrownBy(() -> new RequestFilters("INTERNAL_POLICY' || org_unit_id == 'x", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TenantFilterProvider.requireTenant(Map.of())).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void citationHeadersAndCitationList() {
        Document d1 = Document.builder().id(UUID.randomUUID().toString()).text("Multi-Factor Authentication (MFA) is mandatory.")
                .metadata(Map.of(MetadataKeys.SOURCE, "authentication_datahandling.pdf", MetadataKeys.PAGE_NUMBER, 1))
                .build();
        Document d2 = Document.builder().id(UUID.randomUUID().toString()).text("Incidents.")
                .metadata(Map.of(MetadataKeys.SOURCE, "incident-reporting.docx", MetadataKeys.SECTION_REF, "(¶ 1-8)"))
                .build();
        List<Document> labelled = new CitationHeaderPostProcessor().process(new Query("mfa"), List.of(d1, d2));
        assertThat(labelled.get(0).getText()).startsWith("[S1] [Source: authentication_datahandling.pdf, Page: 1]\n");
        assertThat(labelled.get(1).getText()).startsWith("[S2] [Source: incident-reporting.docx, Section: (¶ 1-8)]\n");

        var cited = CitationExtractor.citations(labelled, "MFA is mandatory for all accounts [S1].");
        assertThat(cited).hasSize(1);
        assertThat(cited.get(0).documentName()).isEqualTo("authentication_datahandling.pdf");
        assertThat(cited.get(0).page()).isEqualTo(1);
        assertThat(cited.get(0).snippet()).startsWith("Multi-Factor Authentication");
    }

    @Test
    void citationSnippetIsTheMostRelevantSentence() {
        String passage = "EAAlgorithm Internal Security Handbook Version 1.0 Confidential - Internal Use Only 1. "
                + "Password & Authentication Requirements Strong authentication practices are critical to preventing "
                + "unauthorized access to company systems and cloud platforms used by all employees and contractors. "
                + "• Multi-Factor Authentication (MFA) is mandatory for all accounts accessing company systems. "
                + "• Passwords must be a minimum of 12 characters and contain uppercase, lowercase, numbers and symbols.";
        Document d = Document.builder().id("x").text(passage).metadata(Map.of(MetadataKeys.SOURCE, "a.pdf")).build();
        Document labelled = new CitationHeaderPostProcessor().process(new Query("How should I handle MFA?"), List.of(d)).get(0);
        assertThat(String.valueOf(labelled.getMetadata().get(MetadataKeys.CHILD_SNIPPET))).startsWith("• Multi-Factor Authentication (MFA)");
    }

    private static Document doc(String id) {
        return Document.builder().id(id).text("text " + id).metadata(Map.of(MetadataKeys.SOURCE, id + ".pdf")).build();
    }
}
