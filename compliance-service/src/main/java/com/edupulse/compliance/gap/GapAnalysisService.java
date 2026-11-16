package com.edupulse.compliance.gap;

import com.edupulse.common.MetadataKeys;
import com.edupulse.common.security.TenantContext;
import com.edupulse.common.web.ApiException;
import com.edupulse.compliance.gap.GapModels.Evidence;
import com.edupulse.compliance.gap.GapModels.ExtractedRequirement;
import com.edupulse.compliance.gap.GapModels.Extraction;
import com.edupulse.compliance.gap.GapModels.Finding;
import com.edupulse.compliance.gap.GapModels.Judgement;
import com.edupulse.compliance.gap.GapModels.Requirement;
import com.edupulse.compliance.gap.client.ServiceClients;
import com.edupulse.compliance.rag.LenientJson;
import com.edupulse.compliance.rag.RagContextKeys;
import com.edupulse.compliance.rag.RagTrace;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Automated gap analysis (agentic workflow):
 * <ol>
 *   <li><b>EXTRACT</b> – the regulation's sections are fetched from the ingestion service (service JWT)
 *       and an LLM turns each one into atomic requirements (structured output).</li>
 *   <li><b>RETRIEVE</b> – for each requirement, hybrid search over the tenant's INTERNAL_POLICY
 *       documents, reranked to the 4 best passages, hydrated to parent sections.</li>
 *   <li><b>JUDGE</b> – an LLM classifies COVERED / PARTIAL / GAP / NOT_APPLICABLE with severity,
 *       rationale, recommendation and the [S#] passages used; evidence is mapped back in code to real
 *       document / page / snippet (same deterministic citation approach as Q&amp;A).</li>
 *   <li><b>AGGREGATE</b> – statistics + executive summary.</li>
 * </ol>
 * Runs asynchronously on virtual threads with bounded LLM concurrency.
 */
@Slf4j
@Service
public class GapAnalysisService {

    private static final Set<String> STATUSES = Set.of("COVERED", "PARTIAL", "GAP", "NOT_APPLICABLE");
    private static final Set<String> SEVERITIES = Set.of("HIGH", "MEDIUM", "LOW");

    private final GapReportRepository reports;
    private final ServiceClients clients;
    private final HybridDocumentRetriever retriever;
    private final CrossEncoderRerankPostProcessor rerank;
    private final ParentContextPostProcessor parents;
    private final CitationHeaderPostProcessor citations;
    private final ChatClient utility;
    private final int maxRequirements;
    private final Semaphore llmPermits;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public GapAnalysisService(GapReportRepository reports, ServiceClients clients, HybridDocumentRetriever retriever,
                              CrossEncoderRerankPostProcessor rerank, ParentContextPostProcessor parents,
                              CitationHeaderPostProcessor citations,
                              @Qualifier("utilityChatClientBuilder") ChatClient.Builder utility,
                              @Value("${edupulse.gap.max-requirements:30}") int maxRequirements,
                              @Value("${edupulse.gap.concurrency:5}") int concurrency) {
        this.reports = reports;
        this.clients = clients;
        this.retriever = retriever;
        this.rerank = rerank;
        this.parents = parents;
        this.citations = citations;
        this.utility = utility.clone().build();
        this.maxRequirements = maxRequirements;
        this.llmPermits = new Semaphore(Math.max(1, concurrency));
    }

    @EventListener(ApplicationReadyEvent.class)
    void failInterruptedReports() {
        int n = reports.failInterrupted();
        if (n > 0) {
            log.warn("Marked {} interrupted gap reports as FAILED", n);
        }
    }

    public UUID start(GapModels.StartRequest req, TenantContext tenant) {
        if (req == null || req.regulationDocumentId() == null) {
            throw ApiException.badRequest("regulationDocumentId is required");
        }
        ServiceClients.DocumentInfo doc;
        try {
            doc = clients.document(req.regulationDocumentId());
        } catch (HttpClientErrorException.NotFound e) {
            throw ApiException.notFound("Regulation document");
        }
        boolean visible = doc != null && doc.organizationId().equals(tenant.organizationId())
                && (tenant.organizationWide() || doc.orgUnitId().equals(tenant.orgUnitId()))
                && doc.clearanceLevel() <= tenant.clearanceLevel();
        if (!visible) {
            throw ApiException.notFound("Regulation document");
        }
        if (!"REGULATION".equals(doc.category())) {
            throw ApiException.badRequest("Document category must be REGULATION (is " + doc.category() + ")");
        }
        if (!"INDEXED".equals(doc.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "Regulation is not indexed yet (status " + doc.status() + "), retry when ingestion finishes");
        }
        UUID reportId = UUID.randomUUID();
        reports.create(reportId, tenant, doc.id(), doc.title() != null ? doc.title() : doc.filename(), req.scope());
        executor.submit(() -> run(reportId, tenant, doc.id(), req.scope()));
        log.info("Gap analysis {} started for regulation {} by {}", reportId, doc.id(), tenant.email());
        return reportId;
    }

    void run(UUID reportId, TenantContext tenant, UUID regulationId, GapModels.Scope scope) {
        try {
            reports.status(reportId, "RUNNING", 2);
            List<ServiceClients.Parent> sections = clients.parents(regulationId);
            List<Requirement> requirements = extract(reportId, sections);
            log.info("Gap analysis {}: {} requirements extracted from {} sections", reportId, requirements.size(), sections.size());

            RequestFilters filters = new RequestFilters("INTERNAL_POLICY",
                    scope != null && scope.docTypes() != null && scope.docTypes().size() == 1 ? scope.docTypes().get(0) : null,
                    null, null);
            AtomicInteger done = new AtomicInteger();
            List<Future<Finding>> futures = new ArrayList<>();
            for (int i = 0; i < requirements.size(); i++) {
                int ordinal = i + 1;
                Requirement r = requirements.get(i);
                futures.add(executor.submit(() -> {
                    Finding f = judge(ordinal, r, tenant, filters);
                    reports.insertFinding(reportId, f);
                    reports.progress(reportId, 30 + (int) (65.0 * done.incrementAndGet() / requirements.size()));
                    return f;
                }));
            }
            List<Finding> findings = new ArrayList<>();
            for (Future<Finding> f : futures) {
                findings.add(f.get());
            }
            Map<String, Long> counts = findings.stream().collect(Collectors.groupingBy(Finding::status, Collectors.counting()));
            String summary = summarize(findings);
            reports.complete(reportId, findings.size(), count(counts, "COVERED"), count(counts, "PARTIAL"),
                    count(counts, "GAP"), count(counts, "NOT_APPLICABLE"), summary);
            log.info("Gap analysis {} completed: {}", reportId, counts);
        } catch (Exception e) {
            log.error("Gap analysis {} failed", reportId, e);
            reports.fail(reportId, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ EXTRACT

    private List<Requirement> extract(UUID reportId, List<ServiceClients.Parent> sections) throws Exception {
        List<Future<List<Requirement>>> futures = new ArrayList<>();
        AtomicInteger done = new AtomicInteger();
        for (ServiceClients.Parent section : sections) {
            futures.add(executor.submit(() -> {
                List<Requirement> out = new ArrayList<>();
                Extraction ex = withRetry(() -> parseExtraction(llm(() -> utility.prompt()
                        .system("""
                                You are a compliance analyst. Break the regulation excerpt into atomic, testable requirements.
                                Only extract real obligations (MUST/SHALL = MUST, SHOULD = SHOULD, MAY = MAY); skip headings,
                                definitions and background text. Keep the original reference (article/clause/control id) when present,
                                otherwise use an empty string. Rephrase each requirement as one clear sentence.
                                Return ONLY JSON, no prose:
                                {"requirements":[{"ref":"1.1","text":"...","obligationType":"MUST"}]}
                                """)
                        .user("Regulation excerpt (page " + (section.pageNumber() == null ? "?" : section.pageNumber()) + "):\n"
                                + section.content())
                        .call().content())));
                if (ex != null && ex.requirements() != null) {
                    for (ExtractedRequirement r : ex.requirements()) {
                        if (r != null && r.text() != null && !r.text().isBlank()) {
                            out.add(new Requirement(r.ref() == null ? "" : r.ref().strip(), r.text().strip(),
                                    section.pageNumber(), normalizeObligation(r.obligationType())));
                        }
                    }
                }
                reports.progress(reportId, 2 + (int) (28.0 * done.incrementAndGet() / Math.max(1, sections.size())));
                return out;
            }));
        }
        Map<String, Requirement> unique = new LinkedHashMap<>();
        for (Future<List<Requirement>> f : futures) {
            for (Requirement r : f.get()) {
                unique.putIfAbsent(r.text().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").strip(), r);
            }
        }
        List<Requirement> all = new ArrayList<>(unique.values());
        return all.size() > maxRequirements ? all.subList(0, maxRequirements) : all;
    }

    // ------------------------------------------------------------------ RETRIEVE + JUDGE

    private Finding judge(int ordinal, Requirement r, TenantContext tenant, RequestFilters filters) {
        Query query = Query.builder().text(r.text()).context(Map.of(
                RagContextKeys.TENANT, tenant, RagContextKeys.FILTERS, filters, RagContextKeys.TRACE, new RagTrace())).build();
        List<Document> docs = retriever.retrieve(query);
        docs = rerank.rerank(r.text(), docs, 4);
        docs = citations.process(query, parents.process(query, docs));

        if (docs.isEmpty()) {
            return new Finding(UUID.randomUUID(), ordinal, r.ref(), r.text(), r.page(), r.obligationType(), "GAP",
                    "MUST".equals(r.obligationType()) ? "HIGH" : "MEDIUM",
                    "No internal policy passage addresses this requirement.",
                    "Create or extend an internal policy that explicitly covers this requirement.", List.of());
        }
        String context = docs.stream().map(Document::getText).collect(Collectors.joining("\n\n---\n\n"));
        Judgement j = withRetry(() -> parseJudgement(llm(() -> utility.prompt()
                .system("""
                        You are an internal auditor comparing ONE regulatory requirement against excerpts of the company's
                        internal policies. Decide:
                        - COVERED: the policies fully satisfy the requirement;
                        - PARTIAL: they address it but miss part of it or are weaker/vaguer (e.g. a longer deadline, lower frequency);
                        - GAP: they do not address it at all;
                        - NOT_APPLICABLE: the requirement clearly does not apply to the company.
                        Severity (for PARTIAL/GAP): HIGH for MUST obligations with security/legal impact, MEDIUM otherwise, LOW for minor items.
                        Base the decision ONLY on the excerpts. In "evidence" list the labels (e.g. "S1") of excerpts you relied on.
                        Rationale: max 2 sentences. Recommendation: one concrete action (empty for COVERED).
                        Return ONLY JSON, no prose:
                        {"status":"COVERED|PARTIAL|GAP|NOT_APPLICABLE","severity":"HIGH|MEDIUM|LOW","rationale":"...","recommendation":"...","evidence":["S1"]}
                        """)
                .user("Requirement (" + r.obligationType() + (r.ref().isBlank() ? "" : ", " + r.ref()) + "): " + r.text()
                        + "\n\nInternal policy excerpts:\n" + context)
                .call().content())));

        String status = normalizeStatus(j == null ? null : j.status());
        String severity = j == null || j.severity() == null ? null : j.severity().strip().toUpperCase(Locale.ROOT);
        if (status.equals("COVERED") || status.equals("NOT_APPLICABLE")) {
            severity = null;   // severity only describes gaps
        } else if (severity == null || !SEVERITIES.contains(severity)) {
            severity = "MEDIUM";
        }
        List<Evidence> evidence = evidence(docs, j == null ? List.of() : j.evidence());
        return new Finding(UUID.randomUUID(), ordinal, r.ref(), r.text(), r.page(), r.obligationType(), status, severity,
                j == null ? "Model returned no judgement." : j.rationale(),
                j == null ? null : j.recommendation(), evidence);
    }

    static List<Evidence> evidence(List<Document> docs, List<String> labels) {
        Set<String> wanted = labels == null ? Set.of() : labels.stream()
                .map(l -> l.replaceAll("[\\[\\]\\s]", "").toUpperCase(Locale.ROOT)).collect(Collectors.toSet());
        List<Evidence> out = new ArrayList<>();
        for (Document d : docs) {
            Map<String, Object> md = d.getMetadata();
            String label = String.valueOf(md.get(MetadataKeys.CITATION_LABEL));
            if (!wanted.isEmpty() && !wanted.contains(label)) {
                continue;
            }
            Object page = md.get(MetadataKeys.PAGE_NUMBER);
            Object snippet = md.getOrDefault(MetadataKeys.CHILD_SNIPPET, md.get(MetadataKeys.SNIPPET));
            out.add(new Evidence(label, str(md.get(MetadataKeys.DOCUMENT_ID)), str(md.get(MetadataKeys.SOURCE)),
                    page instanceof Number n ? n.intValue() : null, str(md.get(MetadataKeys.SECTION_REF)), str(snippet)));
        }
        return out;
    }

    // ------------------------------------------------------------------ AGGREGATE

    private String summarize(List<Finding> findings) {
        if (findings.isEmpty()) {
            return "No testable requirements were found in the regulation.";
        }
        String list = findings.stream()
                .map(f -> "- [" + f.status() + (f.severity() == null ? "" : "/" + f.severity()) + "] " + f.requirementText())
                .collect(Collectors.joining("\n"));
        try {
            return llm(() -> utility.prompt()
                    .system("Write an executive summary (max 150 words) of this compliance gap analysis for management: "
                            + "overall posture, the most important gaps, and the top 3 recommended actions. Plain text, no markdown headings.")
                    .user(list)
                    .call().content());
        } catch (Exception e) {
            log.warn("Summary generation failed: {}", e.getMessage());
            return findings.size() + " requirements analysed.";
        }
    }

    // ------------------------------------------------------------------ tolerant parsing of LLM output

    /** Throws (so {@link #withRetry} retries once) when no JSON can be found. */
    static Extraction parseExtraction(String output) {
        var node = LenientJson.parse(output).orElseThrow(() -> new IllegalStateException("No JSON in extraction output"));
        var list = node.isArray() ? node : LenientJson.field(node, "requirements");
        List<ExtractedRequirement> out = new ArrayList<>();
        if (list != null && list.isArray()) {
            for (var item : list) {
                if (item.isValueNode()) {
                    out.add(new ExtractedRequirement("", item.asString(), "MUST"));
                } else {
                    String text = LenientJson.text(item, "text");
                    if (text.isBlank()) {
                        text = LenientJson.text(item, "requirement");
                    }
                    out.add(new ExtractedRequirement(LenientJson.text(item, "ref"), text, LenientJson.text(item, "obligationType")));
                }
            }
        }
        return new Extraction(out);
    }

    static Judgement parseJudgement(String output) {
        var node = LenientJson.parse(output).orElse(null);
        if (node == null || !node.isObject()) {
            // last resort: a bare status word in prose
            String s = normalizeStatusOrNull(output);
            if (s == null) {
                throw new IllegalStateException("No judgement in model output");
            }
            return new Judgement(s, null, output == null ? null : output.strip(), null, List.of());
        }
        List<String> evidence = new ArrayList<>();
        var ev = LenientJson.field(node, "evidence");
        if (ev != null && ev.isArray()) {
            ev.forEach(e -> evidence.add(e.asString()));
        } else if (ev != null && ev.isValueNode()) {
            for (String s : ev.asString().split("[,;\\s]+")) {
                if (!s.isBlank()) {
                    evidence.add(s);
                }
            }
        }
        return new Judgement(LenientJson.text(node, "status"), LenientJson.text(node, "severity"),
                LenientJson.text(node, "rationale"), LenientJson.text(node, "recommendation"), evidence);
    }

    static String normalizeStatus(String raw) {
        String s = normalizeStatusOrNull(raw);
        return s == null ? "GAP" : s;
    }

    static String normalizeStatusOrNull(String raw) {
        if (raw == null) {
            return null;
        }
        String u = raw.toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        if (u.contains("NOT_APPLICABLE") || u.equals("N/A") || u.equals("NA")) {
            return "NOT_APPLICABLE";
        }
        if (u.contains("PARTIAL")) {
            return "PARTIAL";
        }
        if (u.contains("NOT_COVERED") || u.contains("GAP")) {
            return "GAP";
        }
        if (u.contains("COVERED") || u.contains("COMPLIANT") || u.contains("SATISFIED")) {
            return "COVERED";
        }
        return null;
    }

    // ------------------------------------------------------------------ helpers

    private <T> T llm(Supplier<T> call) {
        try {
            llmPermits.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
        try {
            return call.get();
        } finally {
            llmPermits.release();
        }
    }

    /** Structured output can occasionally be invalid JSON: retry once, then give up on this item only. */
    private static <T> T withRetry(Supplier<T> call) {
        try {
            return call.get();
        } catch (RuntimeException first) {
            try {
                return call.get();
            } catch (RuntimeException second) {
                log.warn("LLM step failed twice: {}", second.getMessage());
                return null;
            }
        }
    }

    private static String normalizeObligation(String o) {
        if (o == null) {
            return "MUST";
        }
        String u = o.strip().toUpperCase(Locale.ROOT);
        return u.startsWith("SHOULD") ? "SHOULD" : u.startsWith("MAY") ? "MAY" : "MUST";
    }

    private static int count(Map<String, Long> counts, String key) {
        return counts.getOrDefault(key, 0L).intValue();
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    /** For tests. */
    static List<String> statuses() {
        return Collections.unmodifiableList(new ArrayList<>(STATUSES));
    }
}
