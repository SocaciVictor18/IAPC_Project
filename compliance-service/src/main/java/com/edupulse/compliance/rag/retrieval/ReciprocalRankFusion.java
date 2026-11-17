package com.edupulse.compliance.rag.retrieval;

import com.edupulse.common.MetadataKeys;
import org.springframework.ai.document.Document;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reciprocal Rank Fusion: merges the semantic and the keyword result lists.
 * score(chunk) = 1/(60 + rank in semantic list) + 1/(60 + rank in keyword list).
 * It uses only positions, so cosine similarity and full-text rank don't need to be comparable.
 */
public final class ReciprocalRankFusion {

    private static final int K = 60;

    private ReciprocalRankFusion() {
    }

    public static List<Document> fuse(List<Document> semantic, List<Document> keyword, int topK) {
        Map<String, Double> scores = new HashMap<>();
        Map<String, Document> docs = new LinkedHashMap<>();
        Map<String, String> match = new HashMap<>();
        addList(semantic, "semantic", scores, docs, match);
        addList(keyword, "keyword", scores, docs, match);

        List<String> ids = new ArrayList<>(scores.keySet());
        ids.sort((a, b) -> Double.compare(scores.get(b), scores.get(a)));

        List<Document> out = new ArrayList<>();
        for (String id : ids.subList(0, Math.min(topK, ids.size()))) {
            Document d = docs.get(id);
            Map<String, Object> md = new HashMap<>(d.getMetadata());
            md.put(MetadataKeys.HYBRID_SCORE, scores.get(id));
            md.put(MetadataKeys.MATCH, match.get(id));   // semantic | keyword | both
            out.add(Document.builder().id(id).text(d.getText()).metadata(md).score(scores.get(id)).build());
        }
        return out;
    }

    private static void addList(List<Document> list, String source, Map<String, Double> scores,
                                Map<String, Document> docs, Map<String, String> match) {
        for (int rank = 1; rank <= list.size(); rank++) {
            Document d = list.get(rank - 1);
            scores.merge(d.getId(), 1.0 / (K + rank), Double::sum);
            docs.putIfAbsent(d.getId(), d);
            match.merge(d.getId(), source, (oldValue, newValue) -> oldValue.equals(newValue) ? oldValue : "both");
        }
    }
}
