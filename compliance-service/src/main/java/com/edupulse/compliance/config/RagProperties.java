package com.edupulse.compliance.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * All tunables of the Advanced RAG pipeline ({@code rag.*}). Evolved from the course's
 * {@code RagConfigData} (synonyms, query expander) with the new hybrid / rerank /
 * compression / guardrail settings.
 */
@ConfigurationProperties("rag")
public record RagProperties(
        String utilityModel,
        int embeddingDimensions,
        /* Insertion order matters: specific patterns must run before broader ones (LinkedHashMap). */
        LinkedHashMap<String, String> synonyms,
        QueryExpander queryExpander,
        Hybrid hybrid,
        Rerank rerank,
        ParentContext parentContext,
        Compression compression,
        TopicBoundary topicBoundary,
        Safeguard safeguard,
        Input input) {

    public Map<String, String> synonymsOrEmpty() {
        return synonyms == null ? Map.of() : synonyms;
    }

    public record QueryExpander(boolean enabled, int numberOfQueries, boolean includeOriginal, int cacheSize) {
    }

    public record Hybrid(int semanticTopK, int keywordTopK, int rrfK, int finalTopK, double similarityThreshold) {
    }

    /** provider: tei | cohere | llm | none */
    public record Rerank(String provider, String url, String apiKey, String cohereModel, int candidates, int topN,
                         int timeoutMs) {
    }

    public record ParentContext(boolean enabled) {
    }

    /** mode: extractive-embedding | llm | none */
    public record Compression(String mode, double sentenceThreshold, int minChars, int maxSentencesPerDocument) {
    }

    public record TopicBoundary(boolean enabled, double acceptThreshold, double greyZoneMin, String refusal) {
    }

    public record Safeguard(List<String> sensitiveWords, String failureResponse) {
    }

    public record Input(int maxQuestionChars, int historyTurns) {
    }
}
