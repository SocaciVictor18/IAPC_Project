package com.edupulse.compliance.rag.preprocessing;

import com.edupulse.common.security.TenantContext;
import com.edupulse.compliance.rag.RagContextKeys;
import com.edupulse.compliance.rag.RagMode;
import com.edupulse.compliance.rag.RagTrace;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.preretrieval.query.expansion.MultiQueryExpander;
import org.springframework.ai.rag.preretrieval.query.expansion.QueryExpander;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Decorates Spring AI's {@link MultiQueryExpander} (FR "Multi-Query Expansion": 3 variants + original):
 * <ul>
 *   <li>records the generated variants in the {@link RagTrace} (audit + "4 queries" acceptance check),</li>
 *   <li>caches variants per (organization, normalised question) – only the TEXTS are cached, the
 *       Query objects are rebuilt from the current request so the current tenant context is kept,</li>
 *   <li>is skipped in {@link RagMode#FAST} and when the expander LLM call fails (degrades to the original query).</li>
 * </ul>
 */
@Slf4j
public class TracingQueryExpander implements QueryExpander {

    private static final java.util.regex.Pattern LIST_PREFIX = java.util.regex.Pattern.compile("^\\s*(?:\\d+[.):]|[-*•])\\s*");

    private final QueryExpander delegate;
    private final Timer timer;
    private final Map<String, List<String>> cache;
    private final ChatClient lenientClient;
    private final String promptTemplate;
    private final int numberOfQueries;

    /**
     * @param lenientClient  used only when the strict MultiQueryExpander parser rejects the model output
     *                       (it requires exactly N clean lines; small local models add numbering or a preamble)
     */
    public TracingQueryExpander(QueryExpander delegate, int cacheSize, MeterRegistry meters,
                                ChatClient lenientClient, String promptTemplate, int numberOfQueries) {
        this.delegate = delegate;
        this.lenientClient = lenientClient;
        this.promptTemplate = promptTemplate;
        this.numberOfQueries = numberOfQueries;
        this.timer = meters.timer("rag.expand");
        this.cache = Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, List<String>> eldest) {
                return size() > Math.max(0, cacheSize);
            }
        });
    }

    @Override
    public List<Query> expand(Query query) {
        RagTrace trace = query.context().get(RagContextKeys.TRACE) instanceof RagTrace t ? t : null;
        if (query.context().get(RagContextKeys.MODE) == RagMode.FAST) {
            record(trace, List.of(query.text()));
            return List.of(query);
        }
        String key = cacheKey(query);
        List<String> texts = cache.get(key);
        if (texts == null) {
            long start = System.nanoTime();
            try {
                texts = timer.recordCallable(() -> delegate.expand(query)).stream().map(Query::text).distinct().toList();
                if (texts.size() <= 1 && numberOfQueries > 0 && lenientClient != null) {
                    texts = timer.recordCallable(() -> lenientExpand(query.text()));
                }
                cache.put(key, texts);
            } catch (Exception e) {
                log.warn("Query expansion failed, continuing with the original query: {}", e.getMessage());
                texts = List.of(query.text());
            } finally {
                if (trace != null) {
                    trace.step("expand", (System.nanoTime() - start) / 1_000_000);
                }
            }
        }
        record(trace, texts);
        log.info("Expanded into {} queries: {}", texts.size(), texts);
        return texts.stream().map(t -> query.mutate().text(t).build()).toList();
    }

    /** Same prompt template, tolerant parsing: strips numbering/bullets/quotes and preamble lines. */
    List<String> lenientExpand(String original) {
        String prompt = promptTemplate.replace("{number}", String.valueOf(numberOfQueries)).replace("{query}", original);
        String output = lenientClient.prompt().user(prompt).call().content();
        List<String> result = new java.util.ArrayList<>();
        result.add(original);
        result.addAll(parseVariants(output, original, numberOfQueries));
        log.info("Lenient expansion produced {} variants", result.size() - 1);
        return result;
    }

    static List<String> parseVariants(String output, String original, int max) {
        List<String> variants = new java.util.ArrayList<>();
        if (output == null) {
            return variants;
        }
        for (String line : output.split("\\R")) {
            String v = LIST_PREFIX.matcher(line).replaceFirst("").replaceAll("^[\"'`*]+|[\"'`*]+$", "").strip();
            String lower = v.toLowerCase(Locale.ROOT);
            if (v.isEmpty() || v.endsWith(":") || lower.startsWith("here ") || lower.startsWith("sure")
                    || lower.startsWith("original query") || v.equalsIgnoreCase(original) || v.split("\\s+").length > 25) {
                continue;
            }
            if (variants.stream().noneMatch(x -> x.equalsIgnoreCase(v))) {
                variants.add(v);
            }
            if (variants.size() == max) {
                break;
            }
        }
        return variants;
    }

    private static void record(RagTrace trace, List<String> texts) {
        if (trace != null) {
            trace.expandedQueries(texts);
        }
    }

    private static String cacheKey(Query query) {
        String org = query.context().get(RagContextKeys.TENANT) instanceof TenantContext t ? t.organizationId().toString() : "-";
        return org + "|" + query.text().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").strip();
    }
}
