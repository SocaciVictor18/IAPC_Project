package com.edupulse.compliance.rag.postprocessing;

import com.edupulse.common.MetadataKeys;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.postretrieval.document.DocumentPostProcessor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Course component, extended with stable labels. Prepends {@code [S1] [Source: file.pdf, Page: 3]}
 * to every context passage so the LLM can cite precisely; the same label is stored in metadata
 * ({@code citation_label}) so the API builds the citation list deterministically (ADR-005).
 * Defensive page keys (page_number / pageNumber / page), "unknown" fallback, and metadata is
 * cloned – never mutated in place.
 */
@Component
public class CitationHeaderPostProcessor implements DocumentPostProcessor {

    private static final String[] PAGE_KEYS = {MetadataKeys.PAGE_NUMBER, "pageNumber", "page"};

    @Override
    public List<Document> process(Query query, List<Document> documents) {
        List<Document> out = new ArrayList<>(documents.size());
        int i = 1;
        for (Document d : documents) {
            String label = "S" + i++;
            Map<String, Object> md = new HashMap<>(d.getMetadata());
            md.put(MetadataKeys.CITATION_LABEL, label);
            Object childText = md.getOrDefault(MetadataKeys.CHILD_SNIPPET, md.get(MetadataKeys.SNIPPET));
            if (childText != null) {
                md.put(MetadataKeys.CHILD_SNIPPET, bestSnippet(String.valueOf(childText), query.text(), SNIPPET_CHARS));
            }
            out.add(d.mutate().text(header(label, md) + "\n" + d.getText()).metadata(md).build());
        }
        return out;
    }

    static final int SNIPPET_CHARS = 300;
    private static final java.util.Set<String> STOP = java.util.Set.of("the", "and", "for", "are", "what", "how", "which",
            "does", "with", "should", "must", "from", "that", "this", "who", "when", "where", "about", "company", "our");

    /**
     * Picks the sentence (plus following ones up to {@code max} chars) of the matched child chunk that
     * shares the most terms with the question, so the citation snippet shows the relevant paragraph
     * rather than whatever the chunk started with (e.g. the document title). Text stays verbatim.
     */
    static String bestSnippet(String text, String question, int max) {
        String flat = text.replaceAll("\\s+", " ").strip();
        if (flat.length() <= max) {
            return flat;
        }
        java.util.Set<String> terms = new java.util.HashSet<>();
        for (String w : (question == null ? "" : question).toLowerCase(java.util.Locale.ROOT).split("[^\\p{L}0-9]+")) {
            if (w.length() >= 3 && !STOP.contains(w)) {
                terms.add(w.length() > 5 ? w.substring(0, 5) : w);   // crude stemming
            }
        }
        List<String> sentences = ContextCompressionPostProcessor.sentences(flat);
        int best = 0;
        int bestScore = -1;
        for (int i = 0; i < sentences.size(); i++) {
            int score = 0;
            for (String w : sentences.get(i).toLowerCase(java.util.Locale.ROOT).split("[^\\p{L}0-9]+")) {
                if (terms.contains(w.length() > 5 ? w.substring(0, 5) : w)) {
                    score++;
                }
            }
            if (score > bestScore) {
                bestScore = score;
                best = i;
            }
        }
        StringBuilder sb = new StringBuilder(sentences.get(best));
        for (int i = best + 1; i < sentences.size() && sb.length() + sentences.get(i).length() + 1 <= max; i++) {
            sb.append(' ').append(sentences.get(i));
        }
        String s = sb.toString();
        return s.length() <= max ? s : s.substring(0, max).strip() + "…";
    }

    static String header(String label, Map<String, Object> md) {
        String source = String.valueOf(md.getOrDefault(MetadataKeys.SOURCE, "unknown"));
        String page = page(md);
        Object section = md.get(MetadataKeys.SECTION_REF);
        if (page.equals("unknown") && section != null) {
            return "[" + label + "] [Source: " + source + ", Section: " + section + "]";
        }
        return "[" + label + "] [Source: " + source + ", Page: " + page + "]";
    }

    static String page(Map<String, Object> md) {
        for (String key : PAGE_KEYS) {
            Object v = md.get(key);
            if (v != null) {
                return v instanceof Number n ? String.valueOf(n.intValue()) : String.valueOf(v);
            }
        }
        return "unknown";
    }
}
