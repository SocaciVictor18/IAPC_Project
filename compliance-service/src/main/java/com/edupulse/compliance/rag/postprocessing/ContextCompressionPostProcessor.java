package com.edupulse.compliance.rag.postprocessing;

import com.edupulse.compliance.config.RagConfigData;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.postretrieval.document.DocumentPostProcessor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Context compression: keeps only the sentences of each section that are relevant to the question,
 * to reduce tokens (cost) and noise. Each sentence is compared with the question using embeddings;
 * sentences above the threshold (plus their neighbours) are kept, word for word, so citations stay exact.
 * <p>
 * Note: Spring AI's CompressionQueryTransformer has a similar name but does something else (it rewrites
 * a follow-up question using the chat history), which is why context compression is done here.
 */
@Slf4j
@Component
public class ContextCompressionPostProcessor implements DocumentPostProcessor {

    private final RagConfigData.Compression props;
    private final EmbeddingModel embeddingModel;

    public ContextCompressionPostProcessor(RagConfigData rag, EmbeddingModel embeddingModel) {
        this.props = rag.compression();
        this.embeddingModel = embeddingModel;
    }

    @Override
    public List<Document> process(Query query, List<Document> documents) {
        if (!props.enabled() || documents.isEmpty()) {
            return documents;
        }
        try {
            List<Document> out = new ArrayList<>();
            float[] question = embeddingModel.embed(query.text());
            for (Document d : documents) {
                out.add(d.mutate().text(compress(question, d.getText())).build());
            }
            return out;
        } catch (Exception e) {
            log.warn("Context compression failed, using the full text: {}", e.getMessage());
            return documents;
        }
    }

    private String compress(float[] question, String text) {
        List<String> sentences = sentences(text);
        List<float[]> vectors = embeddingModel.embed(sentences);
        boolean[] keep = new boolean[sentences.size()];
        for (int i = 0; i < sentences.size(); i++) {
            if (cosine(question, vectors.get(i)) >= props.sentenceThreshold()) {
                keep[i] = true;
                if (i > 0) {
                    keep[i - 1] = true;
                }
                if (i + 1 < sentences.size()) {
                    keep[i + 1] = true;
                }
            }
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < sentences.size(); i++) {
            if (keep[i]) {
                sb.append(sentences.get(i)).append(' ');
            }
        }
        String compressed = sb.toString().strip();
        return compressed.length() >= props.minChars() ? compressed : text;
    }

    /** Splits on sentence ends, new lines and bullet points. */
    static List<String> sentences(String text) {
        List<String> out = new ArrayList<>();
        for (String s : text.split("(?<=[.!?])\\s+(?=[A-Z0-9•\"(])|\\n+|(?=•)")) {
            if (!s.isBlank()) {
                out.add(s.strip());
            }
        }
        return out;
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
