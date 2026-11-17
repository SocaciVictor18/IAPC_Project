package com.edupulse.compliance.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;

/**
 * RAG settings from application.yml ({@code rag.*}). Same idea as the course's RagConfigData
 * (synonyms + query expander), extended with hybrid search, reranking and compression.
 */
@ConfigurationProperties("rag")
public record RagConfigData(
        String utilityModel,
        int embeddingDimensions,
        /* LinkedHashMap keeps the YAML order: specific synonyms are applied before broader ones. */
        LinkedHashMap<String, String> synonyms,
        QueryExpander queryExpander,
        Hybrid hybrid,
        Rerank rerank,
        Compression compression) {

    public record QueryExpander(int numberOfQueries) {
    }

    public record Hybrid(int semanticTopK, int keywordTopK, int finalTopK, double similarityThreshold) {
    }

    public record Rerank(boolean enabled, String url, int topN, int timeoutMs) {
    }

    public record Compression(boolean enabled, double sentenceThreshold, int minChars) {
    }
}
