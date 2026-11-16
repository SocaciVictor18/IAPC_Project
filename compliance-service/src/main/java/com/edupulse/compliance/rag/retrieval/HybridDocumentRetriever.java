package com.edupulse.compliance.rag.retrieval;

import com.edupulse.common.MetadataKeys;
import com.edupulse.common.security.TenantContext;
import com.edupulse.compliance.config.RagProperties;
import com.edupulse.compliance.rag.RagContextKeys;
import com.edupulse.compliance.rag.RagTrace;
import com.edupulse.compliance.rag.RequestFilters;
import com.edupulse.compliance.rag.TenantFilterProvider;
import com.edupulse.compliance.rag.TenantFilterProvider.SqlFilter;
import com.edupulse.compliance.rag.retrieval.ReciprocalRankFusion.RankedList;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Hybrid search (FR "Hybrid Search Execution"): pgvector HNSW cosine similarity + PostgreSQL
 * full-text search on the generated {@code content_tsv} column, executed in parallel on virtual
 * threads and fused with Reciprocal Rank Fusion. Both branches always apply the tenant filter.
 * <p>
 * Spring AI's {@code PgVectorStore} only does vector search; this class is the extension point
 * ({@link DocumentRetriever}) that adds the keyword branch. Full-text search is what catches exact
 * regulatory codes such as "ISO/IEC 27032" or "SOC 2" that embeddings blur.
 */
@Slf4j
@Component
public class HybridDocumentRetriever implements DocumentRetriever {

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };

    /**
     * OR-query over the stemmed lexemes of the question: websearch/plainto_tsquery would AND every
     * word of a natural-language question and almost never match. ts_rank_cd then favours chunks
     * containing more (and closer) terms.
     */
    private static final String KEYWORD_SQL = """
            select id::text as id, content, metadata::text as metadata, ts_rank_cd(content_tsv, q) as rank
            from ingestion.rag_vector_store,
                 to_tsquery('english', coalesce(nullif(array_to_string(array(
                     select quote_literal(l) from unnest(tsvector_to_array(to_tsvector('english', ?))) l), ' | '), ''), 'xqzqx')) q
            where content_tsv @@ q and %s
            order by rank desc
            limit ?
            """;

    private final VectorStore vectorStore;
    private final JdbcClient jdbc;
    private final TenantFilterProvider filters;
    private final RagProperties.Hybrid props;
    private final JsonMapper json = JsonMapper.builder().build();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Timer semanticTimer;
    private final Timer keywordTimer;

    public HybridDocumentRetriever(@Qualifier("ragVectorStore") VectorStore vectorStore, JdbcClient jdbc,
                                   TenantFilterProvider filters, RagProperties props, MeterRegistry meters) {
        this.vectorStore = vectorStore;
        this.jdbc = jdbc;
        this.filters = filters;
        this.props = props.hybrid();
        this.semanticTimer = meters.timer("rag.retrieve.semantic");
        this.keywordTimer = meters.timer("rag.retrieve.keyword");
    }

    @Override
    public List<Document> retrieve(Query query) {
        TenantContext tenant = TenantFilterProvider.requireTenant(query.context());
        RequestFilters requestFilters = TenantFilterProvider.filters(query.context());
        Filter.Expression vectorFilter = filters.vectorFilter(tenant, requestFilters);
        SqlFilter sqlFilter = filters.sqlFilter(tenant, requestFilters);
        RagTrace trace = query.context().get(RagContextKeys.TRACE) instanceof RagTrace t ? t : null;
        if (trace != null) {
            trace.appliedFilter(vectorFilter.toString());
        }

        long start = System.nanoTime();
        CompletableFuture<List<Document>> semantic = CompletableFuture.supplyAsync(
                () -> semanticTimer.record(() -> semanticSearch(query.text(), vectorFilter)), executor);
        CompletableFuture<List<Document>> keyword = CompletableFuture.supplyAsync(
                () -> keywordTimer.record(() -> keywordSearch(query.text(), sqlFilter)), executor);

        List<Document> fused = ReciprocalRankFusion.fuse(
                List.of(new RankedList("semantic", semantic.join()), new RankedList("keyword", keyword.join())),
                props.rrfK(), props.finalTopK());
        if (trace != null) {
            trace.step("retrieve", (System.nanoTime() - start) / 1_000_000);
        }
        log.debug("Hybrid retrieval for '{}': {} fused results", query.text(), fused.size());
        return fused;
    }

    List<Document> semanticSearch(String text, Filter.Expression filter) {
        List<Document> hits = vectorStore.similaritySearch(SearchRequest.builder()
                .query(text)
                .topK(props.semanticTopK())
                .similarityThreshold(props.similarityThreshold())
                .filterExpression(filter)
                .build());
        List<Document> out = new ArrayList<>(hits.size());
        for (Document d : hits) {
            Map<String, Object> md = new HashMap<>(d.getMetadata());
            if (d.getScore() != null) {
                md.put(MetadataKeys.VECTOR_SCORE, d.getScore());
            }
            out.add(Document.builder().id(d.getId()).text(d.getText()).metadata(md).score(d.getScore()).build());
        }
        return out;
    }

    List<Document> keywordSearch(String text, SqlFilter filter) {
        List<Object> params = new ArrayList<>();
        params.add(text);
        params.addAll(filter.params());
        params.add(props.keywordTopK());
        return jdbc.sql(KEYWORD_SQL.formatted(filter.clause()))
                .params(params)
                .query((rs, n) -> {
                    Map<String, Object> md = new HashMap<>(json.readValue(rs.getString("metadata"), MAP));
                    double rank = rs.getDouble("rank");
                    md.put(MetadataKeys.KEYWORD_SCORE, rank);
                    return Document.builder().id(rs.getString("id")).text(rs.getString("content")).metadata(md).score(rank).build();
                })
                .list();
    }
}
