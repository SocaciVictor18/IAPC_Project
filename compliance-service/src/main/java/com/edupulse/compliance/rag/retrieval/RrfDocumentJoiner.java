package com.edupulse.compliance.rag.retrieval;

import com.edupulse.compliance.config.RagProperties;
import com.edupulse.compliance.rag.retrieval.ReciprocalRankFusion.RankedList;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.retrieval.join.DocumentJoiner;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Joins the result lists of the expanded queries. Unlike the default {@code ConcatenationDocumentJoiner}
 * (dedup, keep first occurrence, order = query order), this applies a second RRF level, so a chunk found
 * by several query variants rises to the top. The output is the candidate pool for the reranker.
 */
public class RrfDocumentJoiner implements DocumentJoiner {

    private final int k;
    private final int poolSize;

    public RrfDocumentJoiner(RagProperties props) {
        this.k = props.hybrid().rrfK();
        this.poolSize = Math.max(props.rerank().candidates(), props.hybrid().finalTopK());
    }

    @Override
    public List<Document> join(Map<Query, List<List<Document>>> documentsForQuery) {
        List<RankedList> lists = new ArrayList<>();
        documentsForQuery.forEach((q, perSource) -> perSource.forEach(l -> lists.add(new RankedList("query", l))));
        return ReciprocalRankFusion.fuse(lists, k, poolSize);
    }
}
