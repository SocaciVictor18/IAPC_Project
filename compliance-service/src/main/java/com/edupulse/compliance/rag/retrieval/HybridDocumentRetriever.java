package com.edupulse.compliance.rag.retrieval;

import com.edupulse.common.security.TenantContext;
import com.edupulse.compliance.config.RagConfigData;
import com.edupulse.compliance.rag.RagContextKeys;
import com.edupulse.compliance.rag.RagTrace;
import com.edupulse.compliance.rag.RequestFilters;
import com.edupulse.compliance.rag.TenantFilterProvider;
import com.edupulse.compliance.rag.TenantFilterProvider.SqlFilter;
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Hybrid search (FR "Hybrid Search Execution"):
 * <ul>
 *   <li>semantic search with pgvector (HNSW index, cosine similarity) – finds the meaning,</li>
 *   <li>keyword search with PostgreSQL full-text (tsvector) – finds exact codes like "HIPAA" or "ISO/IEC 27032".</li>
 * </ul>
 * Both run in parallel on virtual threads, both use the tenant filter, and the results are merged with RRF.
 * Spring AI's PgVectorStore only does the semantic part, so this class plugs in as a {@link DocumentRetriever}.
 */
@Component
public class HybridDocumentRetriever implements DocumentRetriever {

    /** Full-text search: the question's words joined with OR ("|"), ranked with ts_rank_cd. */
    private static final String KEYWORD_SQL = """
            select id::text as id, content, metadata::text as metadata
            from ingestion.rag_vector_store,
                 to_tsquery('english', coalesce(nullif(array_to_string(array(
                     select quote_literal(word) from unnest(tsvector_to_array(to_tsvector('english', ?))) word), ' | '), ''), 'xqzqx')) q
            where content_tsv @@ q and %s
            order by ts_rank_cd(content_tsv, q) desc
            limit ?
            """;

    private final VectorStore vectorStore;
    private final JdbcClient jdbc;
    private final TenantFilterProvider filters;
    private final RagConfigData.Hybrid props;
    private final JsonMapper json = JsonMapper.builder().build();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public HybridDocumentRetriever(@Qualifier("ragVectorStore") VectorStore vectorStore, JdbcClient jdbc,
                                   TenantFilterProvider filters, RagConfigData rag) {
        this.vectorStore = vectorStore;
        this.jdbc = jdbc;
        this.filters = filters;
        this.props = rag.hybrid();
    }

    @Override
    public List<Document> retrieve(Query query) {
        TenantContext tenant = TenantFilterProvider.requireTenant(query.context());
        RequestFilters requestFilters = TenantFilterProvider.filters(query.context());
        Filter.Expression vectorFilter = filters.vectorFilter(tenant, requestFilters);
        SqlFilter sqlFilter = filters.sqlFilter(tenant, requestFilters);
        if (query.context().get(RagContextKeys.TRACE) instanceof RagTrace trace) {
            trace.appliedFilter(vectorFilter.toString());
        }

        var semantic = CompletableFuture.supplyAsync(() -> semanticSearch(query.text(), vectorFilter), executor);
        var keyword = CompletableFuture.supplyAsync(() -> keywordSearch(query.text(), sqlFilter), executor);
        return ReciprocalRankFusion.fuse(semantic.join(), keyword.join(), props.finalTopK());
    }

    List<Document> semanticSearch(String text, Filter.Expression filter) {
        return vectorStore.similaritySearch(SearchRequest.builder()
                .query(text)
                .topK(props.semanticTopK())
                .similarityThreshold(props.similarityThreshold())
                .filterExpression(filter)
                .build());
    }

    List<Document> keywordSearch(String text, SqlFilter filter) {
        List<Object> params = new ArrayList<>();
        params.add(text);
        params.addAll(filter.params());
        params.add(props.keywordTopK());
        return jdbc.sql(KEYWORD_SQL.formatted(filter.clause()))
                .params(params)
                .query((rs, n) -> Document.builder()
                        .id(rs.getString("id"))
                        .text(rs.getString("content"))
                        .metadata(json.readValue(rs.getString("metadata"), new TypeReference<Map<String, Object>>() { }))
                        .build())
                .list();
    }
}
