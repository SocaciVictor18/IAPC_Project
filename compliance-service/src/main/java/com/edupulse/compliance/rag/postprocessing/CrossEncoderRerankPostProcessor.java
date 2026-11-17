package com.edupulse.compliance.rag.postprocessing;

import com.edupulse.common.MetadataKeys;
import com.edupulse.compliance.config.RagConfigData;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.postretrieval.document.DocumentPostProcessor;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reranking: the top 20 chunks are re-scored by a local cross-encoder model
 * (BAAI/bge-reranker-base running in the "tei-reranker" container) and the best 8 are kept.
 * A cross-encoder reads question + chunk together, so it judges relevance better than embeddings.
 * If the reranker is down, the original order is kept – the answer never fails because of it.
 */
@Slf4j
@Component
public class CrossEncoderRerankPostProcessor implements DocumentPostProcessor {

    static final int CANDIDATES = 20;

    private final RagConfigData.Rerank props;
    private final RestClient http;

    public CrossEncoderRerankPostProcessor(RagConfigData rag) {
        this.props = rag.rerank();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory();
        factory.setReadTimeout(Duration.ofMillis(props.timeoutMs()));
        this.http = RestClient.builder().requestFactory(factory).build();
    }

    record Score(int index, double score) {
    }

    @Override
    public List<Document> process(Query query, List<Document> documents) {
        return rerank(query.text(), documents, props.topN());
    }

    /** Also used by the gap analysis (with a smaller topN). */
    public List<Document> rerank(String question, List<Document> allResults, int topN) {
        // the results of the 4 queries are merged by Spring AI; keep the 20 best by hybrid (RRF) score
        List<Document> documents = allResults.stream()
                .sorted(Comparator.comparingDouble((Document d) -> d.getScore() == null ? 0 : d.getScore()).reversed())
                .limit(CANDIDATES)
                .toList();
        if (documents.isEmpty() || !props.enabled()) {
            return documents.subList(0, Math.min(topN, documents.size()));
        }
        try {
            List<String> texts = documents.stream().map(Document::getText).toList();
            List<Score> scores = http.post().uri(props.url() + "/rerank")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("query", question, "texts", texts, "truncate", true))
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() { });

            List<Document> reranked = new ArrayList<>();
            for (Score s : scores) {
                Document d = documents.get(s.index());
                Map<String, Object> md = new HashMap<>(d.getMetadata());
                md.put(MetadataKeys.RERANK_SCORE, s.score());
                reranked.add(Document.builder().id(d.getId()).text(d.getText()).metadata(md).score(s.score()).build());
            }
            reranked.sort(Comparator.comparingDouble(Document::getScore).reversed());
            return reranked.subList(0, Math.min(topN, reranked.size()));
        } catch (Exception e) {
            log.warn("Reranker unavailable ({}), keeping the hybrid search order", e.getMessage());
            return documents.subList(0, Math.min(topN, documents.size()));
        }
    }
}
