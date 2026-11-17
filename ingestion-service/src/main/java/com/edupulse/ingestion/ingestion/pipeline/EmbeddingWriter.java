package com.edupulse.ingestion.ingestion.pipeline;

import com.edupulse.ingestion.config.IngestionProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.function.IntConsumer;

/**
 * Embeds and stores child chunks in batches. A global {@link Semaphore} caps concurrent embedding
 * calls across all ingestion pipelines (NFR: 50 simultaneous ingestions must not trip the
 * provider's rate limit), and each batch is retried with exponential backoff (HTTP 429 etc.).
 */
@Slf4j
@Component
public class EmbeddingWriter {

    private static final int MAX_ATTEMPTS = 4;

    private final VectorStore vectorStore;
    private final Semaphore permits;
    private final int batchSize;

    public EmbeddingWriter(@Qualifier("ragVectorStore") VectorStore vectorStore, IngestionProperties props) {
        this.vectorStore = vectorStore;
        this.permits = new Semaphore(Math.max(1, props.embeddingConcurrency()), true);
        this.batchSize = Math.max(1, props.embeddingBatchSize());
    }

    /** @param onProgress receives the number of documents stored so far */
    public void write(List<Document> documents, IntConsumer onProgress) {
        for (int from = 0; from < documents.size(); from += batchSize) {
            List<Document> batch = documents.subList(from, Math.min(documents.size(), from + batchSize));
            writeWithRetry(batch);
            onProgress.accept(Math.min(documents.size(), from + batchSize));
        }
    }

    private void writeWithRetry(List<Document> batch) {
        long backoffMs = 1000;
        for (int attempt = 1; ; attempt++) {
            try {
                permits.acquire();
                try {
                    vectorStore.add(batch);
                    return;
                } finally {
                    permits.release();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while embedding", e);
            } catch (RuntimeException e) {
                if (attempt >= MAX_ATTEMPTS) {
                    throw e;
                }
                log.warn("Embedding batch failed (attempt {}/{}): {} – retrying in {} ms",
                        attempt, MAX_ATTEMPTS, e.getMessage(), backoffMs);
                sleep(backoffMs);
                backoffMs *= 2;
            }
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
