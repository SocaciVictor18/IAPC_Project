package com.edupulse.compliance.gap;

import com.edupulse.common.MetadataKeys;
import com.edupulse.common.crypto.AesGcmCipher;
import com.edupulse.common.security.TenantContext;
import com.edupulse.common.web.ApiException;
import com.edupulse.compliance.gap.GapModels.Evidence;
import com.edupulse.compliance.gap.GapModels.Finding;
import com.edupulse.compliance.gap.GapModels.Judgement;
import com.edupulse.compliance.gap.GapModels.Requirement;
import com.edupulse.compliance.rag.LenientJson;
import com.edupulse.compliance.rag.RagContextKeys;
import com.edupulse.compliance.rag.RequestFilters;
import com.edupulse.compliance.rag.postprocessing.CitationHeaderPostProcessor;
import com.edupulse.compliance.rag.postprocessing.CrossEncoderRerankPostProcessor;
import com.edupulse.compliance.rag.postprocessing.ParentContextPostProcessor;
import com.edupulse.compliance.rag.retrieval.HybridDocumentRetriever;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * Automated gap analysis (agentic workflow), run in the background:
 * <ol>
 *   <li>EXTRACT – the LLM breaks each section of the new regulation into requirements;</li>
 *   <li>RETRIEVE – for each requirement, hybrid search in the INTERNAL_POLICY documents of the same tenant;</li>
 *   <li>JUDGE – the LLM decides COVERED / PARTIAL / GAP and cites the policy passages it used;</li>
 *   <li>REPORT – counts + an executive summary, exportable as PDF or JSON.</li>
 * </ol>
 */
@Slf4j
@Service
public class GapAnalysisService {

    private final GapReportRepository reports;
    private final JdbcClient jdbc;
    private final AesGcmCipher cipher;
    private final HybridDocumentRetriever retriever;
    private final CrossEncoderRerankPostProcessor rerank;
    private final ParentContextPostProcessor parents;
    private final CitationHeaderPostProcessor citations;
    private final ChatClient llm;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public GapAnalysisService(GapReportRepository reports, JdbcClient jdbc, AesGcmCipher cipher,
                              HybridDocumentRetriever retriever, CrossEncoderRerankPostProcessor rerank,
                              ParentContextPostProcessor parents, CitationHeaderPostProcessor citations,
                              @Qualifier("utilityChatClientBuilder") ChatClient.Builder utility) {
        this.reports = reports;
        this.jdbc = jdbc;
        this.cipher = cipher;
        this.retriever = retriever;
        this.rerank = rerank;
        this.parents = parents;
        this.citations = citations;
        this.llm = utility.clone().build();
    }

    /** Checks the regulation and starts the analysis in the background; returns the report id. */
    public UUID start(UUID regulationId, TenantContext tenant) {
        Map<String, Object> doc = jdbc.sql("""
                        select title, category, status, organization_id, org_unit_id, clearance_level
                        from ingestion.document where id = ?
                        """)
                .param(regulationId).query().listOfRows().stream().findFirst()
                .filter(d -> tenant.organizationId().equals(d.get("organization_id")))
                .filter(d -> tenant.organizationWide() || tenant.orgUnitId().equals(d.get("org_unit_id")))
                .filter(d -> ((Number) d.get("clearance_level")).intValue() <= tenant.clearanceLevel())
                .orElseThrow(() -> ApiException.notFound("Regulation document"));
        if (!"REGULATION".equals(doc.get("category"))) {
            throw ApiException.badRequest("The document must have category REGULATION");
        }
        if (!"INDEXED".equals(doc.get("status"))) {
            throw ApiException.conflict("The regulation is not indexed yet");
        }
        UUID reportId = UUID.randomUUID();
        reports.create(reportId, tenant, regulationId, String.valueOf(doc.get("title")));
        executor.submit(() -> run(reportId, regulationId, tenant));
        return reportId;
    }

    void run(UUID reportId, UUID regulationId, TenantContext tenant) {
        try {
            List<Requirement> requirements = extractRequirements(regulationId);
            reports.progress(reportId, 20);
            List<Finding> findings = new ArrayList<>();
            for (int i = 0; i < requirements.size(); i++) {
                Finding f = judge(i + 1, requirements.get(i), tenant);
                reports.insertFinding(reportId, f);
                findings.add(f);
                reports.progress(reportId, 20 + 75 * (i + 1) / requirements.size());
            }
            Map<String, Long> counts = findings.stream().collect(Collectors.groupingBy(Finding::status, Collectors.counting()));
            reports.complete(reportId, findings.size(), count(counts, "COVERED"), count(counts, "PARTIAL"),
                    count(counts, "GAP"), summary(findings));
            log.info("Gap analysis {} completed: {}", reportId, counts);
        } catch (Exception e) {
            log.error("Gap analysis {} failed", reportId, e);
            reports.fail(reportId, e.getMessage());
        }
    }

    // ---------------------------------------------------------------- step 1: EXTRACT

    private List<Requirement> extractRequirements(UUID regulationId) {
        List<Map<String, Object>> sections = jdbc.sql(
                        "select page_number, content_enc from ingestion.parent_chunk where document_id = ? order by parent_index")
                .param(regulationId).query().listOfRows();
        Map<String, Requirement> unique = new LinkedHashMap<>();
        for (Map<String, Object> section : sections) {
            Integer page = (Integer) section.get("page_number");
            String text = cipher.decryptString((byte[]) section.get("content_enc"));
            String output = llm.prompt()
                    .system("""
                            You are a compliance analyst. Split the regulation excerpt into separate, testable requirements
                            (only real obligations: MUST / SHALL / SHOULD). Keep the article or clause number as "ref".
                            Return ONLY JSON: {"requirements":[{"ref":"1.1","text":"..."}]}
                            """)
                    .user(text)
                    .call().content();
            for (JsonNode item : requirementsArray(output)) {
                String reqText = LenientJson.text(item, "text");
                if (!reqText.isBlank()) {
                    unique.putIfAbsent(reqText.toLowerCase(Locale.ROOT), new Requirement(LenientJson.text(item, "ref"), reqText, page));
                }
            }
        }
        return new ArrayList<>(unique.values());
    }

    static List<JsonNode> requirementsArray(String output) {
        JsonNode node = LenientJson.parse(output).orElse(null);
        JsonNode list = node == null ? null : node.isArray() ? node : LenientJson.field(node, "requirements");
        List<JsonNode> out = new ArrayList<>();
        if (list != null && list.isArray()) {
            list.forEach(out::add);
        }
        return out;
    }

    // ---------------------------------------------------------------- steps 2 + 3: RETRIEVE and JUDGE

    private Finding judge(int ordinal, Requirement r, TenantContext tenant) {
        Query query = Query.builder().text(r.text()).context(Map.of(
                RagContextKeys.TENANT, tenant,
                RagContextKeys.FILTERS, new RequestFilters("INTERNAL_POLICY", null))).build();
        List<Document> docs = citations.process(query, parents.process(query, rerank.rerank(r.text(), retriever.retrieve(query), 4)));
        if (docs.isEmpty()) {
            return new Finding(UUID.randomUUID(), ordinal, r.ref(), r.text(), r.page(), "GAP", "HIGH",
                    "No internal policy addresses this requirement.", "Write a policy that covers this requirement.", List.of());
        }
        String context = docs.stream().map(Document::getText).collect(Collectors.joining("\n\n---\n\n"));
        Judgement j = parseJudgement(llm.prompt()
                .system("""
                        You are an internal auditor. Compare ONE regulatory requirement with excerpts of the hospital's policies.
                        COVERED = the policies fully satisfy it; PARTIAL = they address it only partly or more weakly
                        (e.g. a longer deadline); GAP = they do not address it. Severity of a PARTIAL/GAP: HIGH, MEDIUM or LOW.
                        Use only the excerpts and list the labels (e.g. "S1") you relied on.
                        Return ONLY JSON: {"status":"COVERED|PARTIAL|GAP","severity":"HIGH|MEDIUM|LOW",
                        "rationale":"max 2 sentences","recommendation":"one action, empty if COVERED","evidence":["S1"]}
                        """)
                .user("Requirement: " + r.text() + "\n\nPolicy excerpts:\n" + context)
                .call().content());
        String status = normalizeStatus(j.status());
        return new Finding(UUID.randomUUID(), ordinal, r.ref(), r.text(), r.page(), status,
                status.equals("COVERED") ? null : j.severity(), j.rationale(), j.recommendation(), evidence(docs, j.evidence()));
    }

    /** Maps the cited labels back to the real document, page and snippet. */
    static List<Evidence> evidence(List<Document> docs, List<String> labels) {
        List<Evidence> out = new ArrayList<>();
        for (Document d : docs) {
            Map<String, Object> md = d.getMetadata();
            if (labels.isEmpty() || labels.contains(String.valueOf(md.get(MetadataKeys.CITATION_LABEL)))) {
                Object page = md.get(MetadataKeys.PAGE_NUMBER);
                out.add(new Evidence(String.valueOf(md.get(MetadataKeys.SOURCE)), page instanceof Number n ? n.intValue() : null,
                        String.valueOf(md.getOrDefault(MetadataKeys.CHILD_SNIPPET, md.get(MetadataKeys.SNIPPET)))));
            }
        }
        return out;
    }

    /** Small local models do not always return clean JSON, so the answer is parsed tolerantly. */
    static Judgement parseJudgement(String output) {
        JsonNode node = LenientJson.parse(output).orElse(null);
        if (node == null || !node.isObject()) {
            return new Judgement(output, "MEDIUM", output, null, List.of());
        }
        List<String> evidence = new ArrayList<>();
        JsonNode ev = LenientJson.field(node, "evidence");
        if (ev != null && ev.isArray()) {
            ev.forEach(e -> evidence.add(e.asString().replaceAll("[\\[\\]\\s]", "")));
        }
        String severity = LenientJson.text(node, "severity").toUpperCase(Locale.ROOT);
        return new Judgement(LenientJson.text(node, "status"), severity.isBlank() ? "MEDIUM" : severity,
                LenientJson.text(node, "rationale"), LenientJson.text(node, "recommendation"), evidence);
    }

    static String normalizeStatus(String raw) {
        String s = raw == null ? "" : raw.toUpperCase(Locale.ROOT);
        if (s.contains("PARTIAL")) {
            return "PARTIAL";
        }
        if (s.contains("NOT COVERED") || s.contains("NOT_COVERED") || s.contains("GAP")) {
            return "GAP";
        }
        return s.contains("COVERED") ? "COVERED" : "GAP";
    }

    // ---------------------------------------------------------------- step 4: REPORT

    private String summary(List<Finding> findings) {
        String list = findings.stream().map(f -> "- [" + f.status() + "] " + f.requirementText()).collect(Collectors.joining("\n"));
        try {
            return llm.prompt()
                    .system("Write an executive summary (max 120 words) of this compliance gap analysis: overall status, "
                            + "the most important gaps and the top 3 actions. Plain text.")
                    .user(list).call().content();
        } catch (Exception e) {
            return findings.size() + " requirements analysed.";
        }
    }

    private static int count(Map<String, Long> counts, String key) {
        return counts.getOrDefault(key, 0L).intValue();
    }
}
