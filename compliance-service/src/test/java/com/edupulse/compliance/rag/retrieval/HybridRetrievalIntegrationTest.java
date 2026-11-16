package com.edupulse.compliance.rag.retrieval;

import com.edupulse.common.MetadataKeys;
import com.edupulse.common.security.Roles;
import com.edupulse.common.security.TenantContext;
import com.edupulse.compliance.config.RagProperties;
import com.edupulse.compliance.rag.RagContextKeys;
import com.edupulse.compliance.rag.RequestFilters;
import com.edupulse.compliance.rag.TenantFilterProvider;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.rag.Query;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration test on a real pgvector database (Testcontainers): hybrid search returns exact-code
 * keyword matches, and tenant isolation holds on BOTH branches (vector + full-text).
 * Uses a deterministic bag-of-words embedding model so no LLM provider is needed.
 */
@Testcontainers(disabledWithoutDocker = true)
class HybridRetrievalIntegrationTest {

    static final int DIMS = 64;
    static final UUID ORG = UUID.randomUUID();
    static final UUID OU_A = UUID.randomUUID();
    static final UUID OU_B = UUID.randomUUID();
    static final UUID OTHER_ORG = UUID.randomUUID();

    @Container
    static final PostgreSQLContainer PG = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));

    static HybridDocumentRetriever retriever;

    @BeforeAll
    static void setUp() {
        DriverManagerDataSource ds = new DriverManagerDataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        jdbc.execute("create extension if not exists vector");
        jdbc.execute("create schema ingestion");
        jdbc.execute("""
                create table ingestion.rag_vector_store (
                    id uuid primary key default gen_random_uuid(), content text, metadata json, embedding vector(%d),
                    content_tsv tsvector generated always as (to_tsvector('english', coalesce(content, ''))) stored)
                """.formatted(DIMS));

        EmbeddingModel embeddings = new HashingEmbeddingModel();
        VectorStore store = PgVectorStore.builder(jdbc, embeddings).schemaName("ingestion").vectorTableName("rag_vector_store")
                .dimensions(DIMS).initializeSchema(false).build();
        store.add(List.of(
                chunk("Multi-Factor Authentication (MFA) is mandatory for all accounts accessing company systems.", ORG, OU_A, 1, "auth.pdf"),
                chunk("EAAlgorithm aligns with ISO/IEC 27032, NIST CSF, SOC 2 and GDPR frameworks.", ORG, OU_A, 1, "compliance.pdf"),
                chunk("Restricted finance ledger: expenses above 5000 EUR require CFO approval.", ORG, OU_B, 1, "finance.txt"),
                chunk("Top secret merger plan for the security team.", ORG, OU_A, 3, "secret.pdf"),
                chunk("Other organization MFA policy: authentication rules for all accounts.", OTHER_ORG, UUID.randomUUID(), 1, "other.pdf")));

        LinkedHashMap<String, String> synonyms = new LinkedHashMap<>();
        RagProperties props = new RagProperties("m", DIMS, synonyms, null,
                new RagProperties.Hybrid(20, 20, 60, 20, 0.0),
                new RagProperties.Rerank("none", null, null, null, 20, 8, 800),
                null, null, null, null, null, null);
        retriever = new HybridDocumentRetriever(store, JdbcClient.create(ds), new TenantFilterProvider(), props, new SimpleMeterRegistry());
    }

    @Test
    void exactRegulatoryCodeIsFoundByKeywordSearch() {
        List<Document> docs = retriever.retrieve(query("Which frameworks does the company align with (ISO/IEC 27032)?", tenant(OU_A, 1, Roles.EMPLOYEE)));
        assertThat(docs).isNotEmpty();
        Document top = docs.stream().filter(d -> "compliance.pdf".equals(d.getMetadata().get(MetadataKeys.SOURCE))).findFirst().orElseThrow();
        assertThat(String.valueOf(top.getMetadata().get(MetadataKeys.MATCH))).isIn("keyword", "both");
    }

    @Test
    void userOfOrgUnitANeverSeesOrgUnitBOtherOrgOrHigherClearance() {
        TenantContext employeeA = tenant(OU_A, 1, Roles.EMPLOYEE);
        for (String q : List.of("Who approves expenses above 5000 EUR?", "MFA authentication accounts", "merger plan security team")) {
            List<Document> docs = retriever.retrieve(query(q, employeeA));
            assertThat(docs).allSatisfy(d -> {
                assertThat(d.getMetadata().get(MetadataKeys.ORGANIZATION_ID)).isEqualTo(ORG.toString());
                assertThat(d.getMetadata().get(MetadataKeys.ORG_UNIT_ID)).isEqualTo(OU_A.toString());
                assertThat(((Number) d.getMetadata().get(MetadataKeys.CLEARANCE_LEVEL)).intValue()).isLessThanOrEqualTo(1);
            });
        }
    }

    @Test
    void orgUnitBUserSeesItsOwnDocument() {
        List<Document> docs = retriever.retrieve(query("Who approves expenses above 5000 EUR?", tenant(OU_B, 1, Roles.EMPLOYEE)));
        assertThat(docs).extracting(d -> d.getMetadata().get(MetadataKeys.SOURCE)).contains("finance.txt");
    }

    @Test
    void adminSeesAllOrgUnitsOfOwnOrganizationOnly() {
        List<Document> docs = retriever.retrieve(query("MFA authentication accounts expenses approval", tenant(OU_A, 3, Roles.ADMIN)));
        assertThat(docs).extracting(d -> d.getMetadata().get(MetadataKeys.SOURCE)).contains("finance.txt", "auth.pdf").doesNotContain("other.pdf");
    }

    @Test
    void categoryFilterIsAndedOnTopOfTenant() {
        Query q = Query.builder().text("MFA authentication").context(Map.of(
                RagContextKeys.TENANT, tenant(OU_A, 1, Roles.EMPLOYEE),
                RagContextKeys.FILTERS, new RequestFilters("REGULATION", null, null, null))).build();
        assertThat(retriever.retrieve(q)).isEmpty();
    }

    @Test
    void noTenantNoResults() {
        assertThatThrownBy(() -> retriever.retrieve(new Query("MFA"))).isInstanceOf(IllegalStateException.class);
    }

    private static Query query(String text, TenantContext tenant) {
        return Query.builder().text(text).context(Map.of(RagContextKeys.TENANT, tenant)).build();
    }

    private static TenantContext tenant(UUID ou, int clearance, String role) {
        return new TenantContext(UUID.randomUUID(), "t@x.test", ORG, "Acme", ou, clearance, Set.of(role));
    }

    private static Document chunk(String text, UUID org, UUID ou, int clearance, String source) {
        Map<String, Object> md = new HashMap<>();
        md.put(MetadataKeys.ORGANIZATION_ID, org.toString());
        md.put(MetadataKeys.ORG_UNIT_ID, ou.toString());
        md.put(MetadataKeys.CLEARANCE_LEVEL, clearance);
        md.put(MetadataKeys.SOURCE, source);
        md.put(MetadataKeys.CATEGORY, "INTERNAL_POLICY");
        md.put(MetadataKeys.DOCUMENT_ID, UUID.randomUUID().toString());
        return Document.builder().id(UUID.randomUUID().toString()).text(text).metadata(md).build();
    }

    /** Deterministic "embedding": hashed bag of lower-cased words, L2-normalised. */
    static final class HashingEmbeddingModel implements EmbeddingModel {
        @Override
        public EmbeddingResponse call(EmbeddingRequest request) {
            List<Embedding> out = new ArrayList<>();
            int i = 0;
            for (String text : request.getInstructions()) {
                out.add(new Embedding(vector(text), i++));
            }
            return new EmbeddingResponse(out);
        }

        @Override
        public float[] embed(Document document) {
            return vector(document.getText());
        }

        @Override
        public int dimensions() {
            return DIMS;
        }

        static float[] vector(String text) {
            float[] v = new float[DIMS];
            for (String w : text.toLowerCase().split("[^a-z0-9]+")) {
                if (!w.isBlank()) {
                    v[Math.floorMod(new String(w.getBytes(StandardCharsets.UTF_8)).hashCode(), DIMS)] += 1;
                }
            }
            double norm = 0;
            for (float f : v) {
                norm += f * f;
            }
            norm = Math.sqrt(norm == 0 ? 1 : norm);
            for (int k = 0; k < DIMS; k++) {
                v[k] /= (float) norm;
            }
            return v;
        }
    }
}
