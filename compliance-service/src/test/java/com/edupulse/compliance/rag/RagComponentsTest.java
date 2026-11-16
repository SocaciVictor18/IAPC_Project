package com.edupulse.compliance.rag;

import com.edupulse.common.MetadataKeys;
import com.edupulse.common.security.Roles;
import com.edupulse.common.security.TenantContext;
import com.edupulse.compliance.config.RagProperties;
import com.edupulse.compliance.qa.CitationExtractor;
import com.edupulse.compliance.rag.postprocessing.CitationHeaderPostProcessor;
import com.edupulse.compliance.rag.preprocessing.DomainSynonymTransformer;
import com.edupulse.compliance.rag.retrieval.ReciprocalRankFusion;
import com.edupulse.compliance.rag.retrieval.ReciprocalRankFusion.RankedList;
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

    private static RagProperties props() {
        LinkedHashMap<String, String> synonyms = new LinkedHashMap<>();
        synonyms.put("MFA", "multi factor authentication");
        synonyms.put("SSO", "single sign-on");
        synonyms.put("PTO", "paid time off");
        return new RagProperties("m", 1536, synonyms, null, null, null, null, null, null, null, null);
    }

    private static TenantContext tenant(String role) {
        return new TenantContext(UUID.randomUUID(), "u@x.test", UUID.fromString("11111111-1111-1111-1111-111111111111"),
                "Acme", UUID.fromString("11111111-1111-1111-1111-0000000000a1"), 2, Set.of(role));
    }

    // ---- DomainSynonymTransformer (course test matrix)

    @Test
    void synonymTransformerMatchesCourseExample() {
        DomainSynonymTransformer t = new DomainSynonymTransformer(props());
        TenantContext tenant = tenant(Roles.EMPLOYEE);
        Query q = Query.builder().text("how should I handle MFA?").context(Map.of(RagContextKeys.TENANT, tenant)).build();
        Query out = t.transform(q);
        assertThat(out.text()).isEqualTo("how should I handle multi factor authentication");
        // context (tenant!) must survive the transformation
        assertThat(out.context()).containsEntry(RagContextKeys.TENANT, tenant);
    }

    @Test
    void synonymTransformerStripsPoliteNoiseAndRespectsWordBoundaries() {
        DomainSynonymTransformer t = new DomainSynonymTransformer(props());
        assertThat(t.transform(new Query("Please, can you tell me how to handle international travel?")).text())
                .isEqualTo("how to handle international travel");
        assertThat(t.transform(new Query("Is SSO required? I received a pto request")).text())
                .isEqualTo("Is single sign-on required? I received a paid time off request");
        assertThat(t.transform(new Query("Explain the smfa program")).text()).isEqualTo("Explain the smfa program");
    }

    // ---- Reciprocal Rank Fusion

    @Test
    void rrfRanksDocumentsFoundByBothSearchesFirst() {
        Document a = doc("a");
        Document b = doc("b");
        Document c = doc("c");
        List<Document> fused = ReciprocalRankFusion.fuse(List.of(
                new RankedList("semantic", List.of(a, b)),
                new RankedList("keyword", List.of(c, b))), 60, 10);
        assertThat(fused.get(0).getId()).isEqualTo("b");
        assertThat(fused.get(0).getMetadata()).containsEntry(MetadataKeys.MATCH, "both");
        assertThat(fused).extracting(d -> d.getMetadata().get(MetadataKeys.MATCH))
                .containsExactly("both", "semantic", "keyword");
        double expected = 1.0 / 62 + 1.0 / 62;
        assertThat((Double) fused.get(0).getMetadata().get(MetadataKeys.HYBRID_SCORE)).isEqualTo(expected);
    }

    @Test
    void secondLevelFusionKeepsMatchProvenance() {
        List<Document> q1 = ReciprocalRankFusion.fuse(List.of(new RankedList("keyword", List.of(doc("x")))), 60, 10);
        List<Document> q2 = ReciprocalRankFusion.fuse(List.of(new RankedList("semantic", List.of(doc("x")))), 60, 10);
        List<Document> joined = ReciprocalRankFusion.fuse(List.of(new RankedList("query", q1), new RankedList("query", q2)), 60, 10);
        assertThat(joined.get(0).getMetadata()).containsEntry(MetadataKeys.MATCH, "both");
    }

    // ---- Tenant filter (cannot be bypassed)

    @Test
    void tenantFilterAlwaysContainsOrgUnitAndClearance() {
        TenantFilterProvider p = new TenantFilterProvider();
        TenantContext employee = tenant(Roles.EMPLOYEE);
        String expr = p.vectorFilter(employee, RequestFilters.NONE).toString();
        assertThat(expr).contains("organization_id").contains(employee.organizationId().toString())
                .contains("org_unit_id").contains(employee.orgUnitId().toString())
                .contains("clearance_level");

        TenantFilterProvider.SqlFilter sql = p.sqlFilter(employee, new RequestFilters("INTERNAL_POLICY", null, null, null));
        assertThat(sql.clause()).contains("organization_id").contains("org_unit_id").contains("clearance_level").contains("category");
        assertThat(sql.clause()).doesNotContain(employee.orgUnitId().toString()); // values are bound, never concatenated
        assertThat(sql.params()).contains(employee.orgUnitId().toString(), 2, "INTERNAL_POLICY");
    }

    @Test
    void adminFilterIsOrganizationWide() {
        String expr = new TenantFilterProvider().vectorFilter(tenant(Roles.ADMIN), RequestFilters.NONE).toString();
        assertThat(expr).contains("organization_id").doesNotContain("org_unit_id");
    }

    @Test
    void clientFiltersAreWhitelisted() {
        assertThatThrownBy(() -> new RequestFilters("INTERNAL_POLICY' || org_unit_id == 'x", null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RequestFilters(null, "x' OR 1=1 --", null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void retrievalFailsClosedWithoutTenant() {
        assertThatThrownBy(() -> TenantFilterProvider.requireTenant(Map.of())).isInstanceOf(IllegalStateException.class);
    }

    // ---- Citations

    @Test
    void citationHeadersAndDeterministicCitationList() {
        Document d1 = Document.builder().id(UUID.randomUUID().toString()).text("MFA is mandatory.")
                .metadata(Map.of(MetadataKeys.SOURCE, "authentication_datahandling.pdf", MetadataKeys.PAGE_NUMBER, 1,
                        MetadataKeys.SNIPPET, "Multi-Factor Authentication (MFA) is mandatory"))
                .build();
        Document d2 = Document.builder().id(UUID.randomUUID().toString()).text("Remote work.")
                .metadata(Map.of(MetadataKeys.SOURCE, "remote.docx", MetadataKeys.SECTION_REF, "(¶ 1-8)"))
                .build();
        List<Document> labelled = new CitationHeaderPostProcessor().process(new Query("q"), List.of(d1, d2));
        assertThat(labelled.get(0).getText()).startsWith("[S1] [Source: authentication_datahandling.pdf, Page: 1]\n");
        assertThat(labelled.get(1).getText()).startsWith("[S2] [Source: remote.docx, Section: (¶ 1-8)]\n");
        assertThat(d1.getMetadata()).doesNotContainKey(MetadataKeys.CITATION_LABEL); // original not mutated

        var cited = CitationExtractor.citations(labelled, "MFA is mandatory for all accounts [S1].");
        assertThat(cited).hasSize(1);
        assertThat(cited.get(0).documentName()).isEqualTo("authentication_datahandling.pdf");
        assertThat(cited.get(0).page()).isEqualTo(1);
        assertThat(cited.get(0).snippet()).startsWith("Multi-Factor Authentication");

        var uncited = CitationExtractor.citations(labelled, "No labels here.");
        assertThat(uncited).hasSize(2).allSatisfy(c -> assertThat(c.cited()).isFalse());
    }

    private static Document doc(String id) {
        return Document.builder().id(id).text("text " + id).metadata(Map.of(MetadataKeys.SOURCE, id + ".pdf")).build();
    }
}
