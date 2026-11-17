package com.edupulse.compliance.rag.postprocessing;

import com.edupulse.common.MetadataKeys;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.postretrieval.document.DocumentPostProcessor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Post-processor from the course: puts a header like {@code [S1] [Source: file.pdf, Page: 3]} in front of
 * every passage, so the LLM can cite exactly. The label S1, S2… is also stored in the metadata, and the API
 * builds the citation list from it (document, page, snippet) instead of trusting the generated text.
 * Defensive page keys (page_number / pageNumber / page), "unknown" fallback, metadata is copied, not changed.
 */
@Component
public class CitationHeaderPostProcessor implements DocumentPostProcessor {

    static final int SNIPPET_CHARS = 300;
    private static final String[] PAGE_KEYS = {MetadataKeys.PAGE_NUMBER, "pageNumber", "page"};

    @Override
    public List<Document> process(Query query, List<Document> documents) {
        List<Document> out = new ArrayList<>();
        int i = 1;
        for (Document d : documents) {
            String label = "S" + i++;
            Map<String, Object> md = new HashMap<>(d.getMetadata());
            md.put(MetadataKeys.CITATION_LABEL, label);
            // the snippet shown in the citation = the most relevant sentence of the passage the LLM received
            md.put(MetadataKeys.CHILD_SNIPPET, bestSnippet(d.getText(), query.text(), SNIPPET_CHARS));
            out.add(d.mutate().text(header(label, md) + "\n" + d.getText()).metadata(md).build());
        }
        return out;
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

    /** Picks the sentence of the matched chunk that shares the most words with the question (text stays verbatim). */
    static String bestSnippet(String text, String question, int max) {
        String flat = text.replaceAll("\\s+", " ").strip();
        if (flat.length() <= max) {
            return flat;
        }
        Set<String> words = new HashSet<>();
        for (String w : question.toLowerCase(Locale.ROOT).split("[^\\p{L}0-9]+")) {
            if (w.length() >= 3 && !STOP_WORDS.contains(w)) {
                words.add(stem(w));
            }
        }
        List<String> sentences = ContextCompressionPostProcessor.sentences(flat);
        int best = 0;
        int bestScore = -1;
        for (int i = 0; i < sentences.size(); i++) {
            int score = 0;
            for (String w : sentences.get(i).toLowerCase(Locale.ROOT).split("[^\\p{L}0-9]+")) {
                if (words.contains(stem(w))) {
                    score++;
                }
            }
            if (score > bestScore) {
                bestScore = score;
                best = i;
            }
        }
        StringBuilder sb = new StringBuilder(sentences.get(best));
        for (int i = best + 1; i < sentences.size() && sb.length() + sentences.get(i).length() < max; i++) {
            sb.append(' ').append(sentences.get(i));
        }
        return sb.length() <= max ? sb.toString() : sb.substring(0, max) + "…";
    }

    private static final Set<String> STOP_WORDS = Set.of("the", "and", "who", "may", "how", "for", "are", "can", "you",
            "what", "which", "when", "where", "does", "should", "with", "our", "this", "that", "from", "about");

    /** Very simple stemming: "patients" and "patient" both become "patie". */
    private static String stem(String w) {
        return w.length() > 5 ? w.substring(0, 5) : w;
    }
}
