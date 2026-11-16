package com.edupulse.compliance.rag;

/**
 * FULL – synonyms, multi-query expansion, hybrid search, rerank, parent context, compression.
 * FAST – skips the LLM-based expansion and the compression step (latency budget &lt; 2 s).
 */
public enum RagMode {
    FULL, FAST
}
