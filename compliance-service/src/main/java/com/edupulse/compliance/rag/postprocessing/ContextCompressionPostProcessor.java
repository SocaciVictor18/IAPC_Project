package com.edupulse.compliance.rag.postprocessing;

import com.edupulse.compliance.config.RagProperties;
import com.edupulse.compliance.rag.RagContextKeys;
import com.edupulse.compliance.rag.RagMode;
import com.edupulse.compliance.rag.RagTrace;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.postretrieval.document.DocumentPostProcessor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Context compression: distils each retrieved section to the sentences that answer the question,
 * reducing tokens and noise before generation.
 * <ul>
 *   <li>{@code extractive-embedding} (default): sentence embeddings vs. question embedding, keep
 *       sentences ≥ threshold plus their immediate neighbours. Text stays VERBATIM, so citations
 *       remain real quotes, and no extra LLM call is needed.</li>
 *   <li>{@code llm}: a small model extracts relevant sentences verbatim; output is accepted only if every
 *       sentence occurs in the original, otherwise the full text is kept.</li>
 *   <li>{@code none}: pass-through (for the evaluation comparison).</li>
 * </ul>
 * Spring AI's {@code CompressionQueryTransformer} does something else (it compresses conversation
 * history into a standalone query), which is not what the requirement asks for, so it is not used.
 */
@Slf4j
@Component
public class ContextCompressionPostProcessor implements DocumentPostProcessor {

    private static final Pattern SENTENCE_SPLIT = Pattern.compile("(?<=[.!?])\\s+(?=[A-Z0-9•\"(])|\\n+|(?=•)");

    private final RagProperties.Compression props;
    private final EmbeddingModel embeddingModel;
    private final ChatClient.Builder utility;
    private final Timer timer;

    public ContextCompressionPostProcessor(RagProperties props, EmbeddingModel embeddingModel,
                                           @Qualifier("utilityChatClientBuilder") ChatClient.Builder utility,
                                           MeterRegistry meters) {
        this.props = props.compression();
        this.embeddingModel = embeddingModel;
        this.utility = utility;
        this.timer = meters.timer("rag.compress");
    }

    @Override
    public List<Document> process(Query query, List<Document> documents) {
        String mode = props.mode() == null ? "none" : props.mode();
        if (documents.isEmpty() || mode.equals("none") || query.context().get(RagContextKeys.MODE) == RagMode.FAST) {
            return documents;
        }
        long start = System.nanoTime();
        try {
            return timer.recordCallable(() -> switch (mode) {
                case "llm" -> documents.stream().map(d -> llmCompress(query.text(), d)).toList();
                default -> extractive(query.text(), documents);
            });
        } catch (Exception e) {
            log.warn("Context compression failed, using full context: {}", e.getMessage());
            return documents;
        } finally {
            if (query.context().get(RagContextKeys.TRACE) instanceof RagTrace trace) {
                trace.step("compress", (System.nanoTime() - start) / 1_000_000);
            }
        }
    }

    static List<String> sentences(String text) {
        List<String> out = new ArrayList<>();
        for (String s : SENTENCE_SPLIT.split(text)) {
            String t = s.strip();
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }

    private List<Document> extractive(String question, List<Document> documents) {
        List<List<String>> perDoc = new ArrayList<>();
        List<String> batch = new ArrayList<>();
        batch.add(question);
        for (Document d : documents) {
            List<String> s = sentences(d.getText());
            if (s.size() > props.maxSentencesPerDocument()) {
                s = s.subList(0, props.maxSentencesPerDocument());
            }
            perDoc.add(s);
            batch.addAll(s);
        }
        List<float[]> vectors = embeddingModel.embed(batch);
        float[] q = vectors.get(0);
        int cursor = 1;
        List<Document> out = new ArrayList<>(documents.size());
        for (int i = 0; i < documents.size(); i++) {
            Document d = documents.get(i);
            List<String> s = perDoc.get(i);
            boolean[] keep = new boolean[s.size()];
            for (int j = 0; j < s.size(); j++) {
                if (cosine(q, vectors.get(cursor + j)) >= props.sentenceThreshold()) {
                    keep[j] = true;
                    if (j > 0) {
                        keep[j - 1] = true;
                    }
                    if (j + 1 < s.size()) {
                        keep[j + 1] = true;
                    }
                }
            }
            cursor += s.size();
            StringBuilder sb = new StringBuilder();
            for (int j = 0; j < s.size(); j++) {
                if (keep[j]) {
                    sb.append(s.get(j)).append(' ');
                } else if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '…') {
                    sb.append("… ");
                }
            }
            String compressed = sb.toString().strip();
            String text = compressed.length() >= props.minChars() ? compressed : d.getText();
            out.add(d.mutate().text(text).build());
        }
        return out;
    }

    private Document llmCompress(String question, Document d) {
        String extracted = utility.clone().build().prompt()
                .system("Extract, VERBATIM, only the sentences of the passage that help answer the question. "
                        + "Do not rephrase, summarise or add anything. One sentence per line. If nothing is relevant, return NONE.")
                .user("Question: " + question + "\n\nPassage:\n" + d.getText())
                .call().content();
        if (extracted == null || extracted.isBlank() || extracted.strip().equals("NONE")) {
            return d;
        }
        String normalizedOriginal = d.getText().replaceAll("\\s+", " ");
        List<String> lines = extracted.lines().map(String::strip).filter(l -> !l.isEmpty()).toList();
        boolean verbatim = lines.stream().allMatch(l -> normalizedOriginal.contains(l.replaceAll("\\s+", " ")));
        String joined = String.join(" ", lines);
        return verbatim && joined.length() >= props.minChars() ? d.mutate().text(joined).build() : d;
    }

    static double cosine(float[] a, float[] b) {
        double dot = 0;
        double na = 0;
        double nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        return na == 0 || nb == 0 ? 0 : dot / (Math.sqrt(na) * Math.sqrt(nb));
    }
}
