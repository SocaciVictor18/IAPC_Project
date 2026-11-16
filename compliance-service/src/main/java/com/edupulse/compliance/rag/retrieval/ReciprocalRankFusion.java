package com.edupulse.compliance.rag.retrieval;

import com.edupulse.common.MetadataKeys;
import org.springframework.ai.document.Document;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reciprocal Rank Fusion: {@code score(d) = Σ 1 / (k + rank_i(d))}, k = 60.
 * Rank-based, so it fuses cosine similarities and ts_rank values without having to normalise them.
 * The fused score is written to {@code hybrid_score}; {@code match} records whether a chunk was found
 * by the semantic search, the keyword search, or both.
 */
public final class ReciprocalRankFusion {

    private ReciprocalRankFusion() {
    }

    public record RankedList(String source, List<Document> documents) {
    }

    public static List<Document> fuse(List<RankedList> lists, int k, int topK) {
        Map<String, Double> scores = new HashMap<>();
        Map<String, Document> firstSeen = new LinkedHashMap<>();
        Map<String, java.util.Set<String>> sources = new HashMap<>();
        Map<String, Map<String, Object>> extra = new HashMap<>();

        for (RankedList list : lists) {
            int rank = 1;
            for (Document d : list.documents()) {
                String id = d.getId();
                scores.merge(id, 1.0 / (k + rank), Double::sum);
                firstSeen.putIfAbsent(id, d);
                java.util.Set<String> matched = sources.computeIfAbsent(id, x -> new java.util.TreeSet<>());
                Object previousMatch = d.getMetadata().get(MetadataKeys.MATCH);
                if (previousMatch != null) {
                    // second-level fusion (across expanded queries): keep what the first level found
                    for (String m : String.valueOf(previousMatch).split("\\|")) {
                        if (!m.isBlank() && !m.equals("both")) {
                            matched.add(m);
                        } else if (m.equals("both")) {
                            matched.add("semantic");
                            matched.add("keyword");
                        }
                    }
                } else {
                    matched.add(list.source());
                }
                Map<String, Object> e = extra.computeIfAbsent(id, x -> new HashMap<>());
                copyMax(d, e, MetadataKeys.VECTOR_SCORE);
                copyMax(d, e, MetadataKeys.KEYWORD_SCORE);
                rank++;
            }
        }

        List<Map.Entry<String, Double>> ranked = new ArrayList<>(scores.entrySet());
        ranked.sort(Map.Entry.<String, Double>comparingByValue().reversed());

        List<Document> out = new ArrayList<>();
        for (Map.Entry<String, Double> entry : ranked.subList(0, Math.min(topK, ranked.size()))) {
            Document d = firstSeen.get(entry.getKey());
            Map<String, Object> md = new HashMap<>(d.getMetadata());
            md.putAll(extra.get(entry.getKey()));
            md.put(MetadataKeys.HYBRID_SCORE, entry.getValue());
            java.util.Set<String> matched = sources.get(entry.getKey());
            md.put(MetadataKeys.MATCH, matched.size() > 1 ? "both" : matched.iterator().next());
            out.add(Document.builder().id(d.getId()).text(d.getText()).metadata(md).score(entry.getValue()).build());
        }
        return out;
    }

    private static void copyMax(Document d, Map<String, Object> target, String key) {
        Object v = d.getMetadata().get(key);
        if (v instanceof Number n) {
            Object prev = target.get(key);
            if (!(prev instanceof Number p) || p.doubleValue() < n.doubleValue()) {
                target.put(key, n.doubleValue());
            }
        }
    }
}
