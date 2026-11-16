package com.edupulse.ingestion.config;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The RAG vector store bean. {@code initializeSchema=false}: Flyway owns the table because it
 * carries the extra {@code content_tsv} column. The embedding model is chosen by
 * {@code spring.ai.model.embedding=openai|ollama} (no {@code @Primary} trick needed).
 */
@Configuration(proxyBeanMethods = false)
public class VectorStoreConfig {

    @Bean("ragVectorStore")
    VectorStore ragVectorStore(JdbcTemplate jdbcTemplate, EmbeddingModel embeddingModel, IngestionProperties props) {
        return PgVectorStore.builder(jdbcTemplate, embeddingModel)
                .schemaName("ingestion")
                .vectorTableName("rag_vector_store")
                .dimensions(props.embeddingDimensions())
                .distanceType(PgVectorStore.PgDistanceType.COSINE_DISTANCE)
                .indexType(PgVectorStore.PgIndexType.HNSW)
                .initializeSchema(false)
                .maxDocumentBatchSize(props.embeddingBatchSize())
                .build();
    }

    /** Java 21 virtual threads for parallel page normalisation and embedding batches. */
    @Bean(destroyMethod = "shutdown")
    ExecutorService ingestionExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
