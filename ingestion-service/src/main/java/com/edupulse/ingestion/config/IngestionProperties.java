package com.edupulse.ingestion.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Tunables of the ingestion pipeline ({@code edupulse.ingestion.*}). */
@ConfigurationProperties("edupulse.ingestion")
public record IngestionProperties(
        String storageRoot,
        long asyncThresholdBytes,
        long maxUploadBytes,
        int embeddingDimensions,
        int embeddingBatchSize,
        int embeddingConcurrency,
        int maxQueuedJobs,
        Chunking chunking) {

    public record Chunking(
            int parentTokens,
            int parentMinChars,
            int childTokens,
            int childMinChars,
            int childOverlapWords,
            int txtLinesPerPage,
            int docxParagraphsPerSection) {
    }
}
