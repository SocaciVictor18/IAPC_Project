package com.edupulse.compliance.rag.postprocessing;

import com.edupulse.common.MetadataKeys;
import com.edupulse.compliance.config.RagProperties;
import com.edupulse.compliance.rag.RagContextKeys;
import com.edupulse.compliance.rag.RagTrace;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.postretrieval.document.DocumentPostProcessor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reranking (post-retrieval). Spring AI has no built-in reranker; {@link DocumentPostProcessor} is the
 * official extension point. The top {@code candidates} (20) chunks by RRF score are re-scored against the
 * ORIGINAL question by a cross-encoder and the best {@code topN} (8) are kept.
 * <p>
 * Providers: {@code tei} (local HuggingFace Text-Embeddings-Inference with {@code BAAI/bge-reranker-base}),
 * {@code cohere} (Cohere Rerank API), {@code llm} (LLM scoring fallback), {@code none}.
 * Reranking must never fail a request: on timeout/error the RRF order is kept.
 */
@Slf4j
@Component
public class CrossEncoderRerankPostProcessor implements DocumentPostProcessor {

    private final RagProperties.Rerank props;
    private final RestClient http;
    private final ChatClient.Builder utility;
    private final Timer timer;

    public CrossEncoderRerankPostProcessor(RagProperties props, @Qualifier("utilityChatClientBuilder") ChatClient.Builder utility,
                                           MeterRegistry meters) {
        this.props = props.rerank();
        this.utility = utility;
        this.timer = meters.timer("rag.rerank");
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(this.props.timeoutMs())).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(client);
        factory.setReadTimeout(Duration.ofMillis(this.props.timeoutMs()));
        this.http = RestClient.builder().requestFactory(factory).build();
    }

    @Override
    public List<Document> process(Query query, List<Document> documents) {
        if (documents.isEmpty()) {
            return documents;
        }
        RagTrace trace = query.context().get(RagContextKeys.TRACE) instanceof RagTrace t ? t : null;
        if (trace != null) {
            trace.candidates(documents.size());
        }
        long start = System.nanoTime();
        List<Document> result = timer.record(() -> rerank(query.text(), documents, props.topN()));
        if (trace != null) {
            trace.step("rerank", (System.nanoTime() - start) / 1_000_000);
        }
        return result;
    }

    /** Public so the gap-analysis job can reuse it with a different {@code topN}. */
    public List<Document> rerank(String question, List<Document> documents, int topN) {
        List<Document> pool = documents.subList(0, Math.min(props.candidates(), documents.size()));
        String provider = props.provider() == null ? "none" : props.provider().toLowerCase();
        if (provider.equals("none")) {
            return pool.subList(0, Math.min(topN, pool.size()));
        }
        try {
            List<String> texts = pool.stream().map(d -> snippetForScoring(d)).toList();
            double[] scores = switch (provider) {
                case "tei" -> teiScores(question, texts);
                case "cohere" -> cohereScores(question, texts);
                case "llm" -> llmScores(question, texts);
                default -> throw new IllegalArgumentException("Unknown rerank provider " + provider);
            };
            List<Document> scored = new ArrayList<>(pool.size());
            for (int i = 0; i < pool.size(); i++) {
                Document d = pool.get(i);
                Map<String, Object> md = new HashMap<>(d.getMetadata());
                md.put(MetadataKeys.RERANK_SCORE, scores[i]);
                scored.add(Document.builder().id(d.getId()).text(d.getText()).metadata(md).score(scores[i]).build());
            }
            scored.sort(Comparator.comparingDouble((Document d) -> d.getScore() == null ? 0 : d.getScore()).reversed());
            return scored.subList(0, Math.min(topN, scored.size()));
        } catch (Exception e) {
            log.warn("Reranker '{}' unavailable ({}), keeping RRF order", provider, e.getMessage());
            return pool.subList(0, Math.min(topN, pool.size()));
        }
    }

    private static String snippetForScoring(Document d) {
        String t = d.getText() == null ? "" : d.getText();
        return t.length() > 2000 ? t.substring(0, 2000) : t;
    }

    record TeiScore(int index, double score) {
    }

    private double[] teiScores(String question, List<String> texts) {
        List<TeiScore> body = http.post().uri(props.url() + "/rerank")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("query", question, "texts", texts, "truncate", true))
                .retrieve()
                .body(new ParameterizedTypeReference<>() {
                });
        double[] scores = new double[texts.size()];
        if (body != null) {
            body.forEach(s -> scores[s.index()] = s.score());
        }
        return scores;
    }

    record CohereResult(int index, double relevance_score) {
    }

    record CohereResponse(List<CohereResult> results) {
    }

    private double[] cohereScores(String question, List<String> texts) {
        CohereResponse body = http.post().uri(props.url() == null || props.url().isBlank() ? "https://api.cohere.com/v2/rerank" : props.url())
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + props.apiKey())
                .body(Map.of("model", props.cohereModel() == null ? "rerank-v3.5" : props.cohereModel(),
                        "query", question, "documents", texts, "top_n", texts.size()))
                .retrieve()
                .body(CohereResponse.class);
        double[] scores = new double[texts.size()];
        if (body != null && body.results() != null) {
            body.results().forEach(r -> scores[r.index()] = r.relevance_score());
        }
        return scores;
    }

    private double[] llmScores(String question, List<String> texts) {
        StringBuilder passages = new StringBuilder();
        for (int i = 0; i < texts.size(); i++) {
            String t = texts.get(i);
            passages.append('[').append(i).append("] ").append(t, 0, Math.min(600, t.length())).append("\n\n");
        }
        List<Double> scores = utility.clone().build().prompt()
                .system("You score how relevant each passage is to the question, from 0 (irrelevant) to 10 (directly answers it). "
                        + "Return only a JSON array of numbers, one per passage, in the same order.")
                .user("Question: " + question + "\n\nPassages:\n" + passages)
                .call()
                .entity(new ParameterizedTypeReference<List<Double>>() {
                });
        double[] out = new double[texts.size()];
        for (int i = 0; i < out.length && scores != null && i < scores.size(); i++) {
            out[i] = scores.get(i) == null ? 0 : scores.get(i) / 10.0;
        }
        return out;
    }
}
