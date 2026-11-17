package com.edupulse.compliance.config;

import com.edupulse.compliance.rag.postprocessing.CitationHeaderPostProcessor;
import com.edupulse.compliance.rag.postprocessing.ContextCompressionPostProcessor;
import com.edupulse.compliance.rag.postprocessing.CrossEncoderRerankPostProcessor;
import com.edupulse.compliance.rag.postprocessing.ParentContextPostProcessor;
import com.edupulse.compliance.rag.preprocessing.DomainSynonymTransformer;
import com.edupulse.compliance.rag.preprocessing.SafeMultiQueryExpander;
import com.edupulse.compliance.rag.retrieval.HybridDocumentRetriever;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.rag.generation.augmentation.ContextualQueryAugmenter;
import org.springframework.ai.rag.preretrieval.query.expansion.MultiQueryExpander;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;

/**
 * Wiring of the Advanced RAG pipeline (same role as the course's AIProviderConfig).
 * The provider (OpenAI / Ollama / Claude) is chosen in application.yml with
 * {@code spring.ai.model.chat} and {@code spring.ai.model.embedding}.
 * <pre>
 * RetrievalAugmentationAdvisor
 *   pre-retrieval:  DomainSynonymTransformer → MultiQueryExpander (3 variants + original)
 *   retrieval:      HybridDocumentRetriever (pgvector + full-text, tenant filter)
 *   post-retrieval: Rerank (top 20 → 8) → ParentContext → ContextCompression → CitationHeader
 *   generation:     ContextualQueryAugmenter (no context → "I don't know")
 * </pre>
 */
@Configuration(proxyBeanMethods = false)
public class AIProviderConfig {

    /** Small model at temperature 0 for the query expansion. */
    @Bean
    @Qualifier("utilityChatClientBuilder")
    ChatClient.Builder utilityChatClientBuilder(ChatModel chatModel, RagConfigData rag) {
        ChatOptions.Builder<?> options = chatModel.getDefaultOptions().mutate().maxTokens(1024);
        if (rag.utilityModel() != null && !rag.utilityModel().isBlank()) {
            options.model(rag.utilityModel());
        }
        // Current Claude models reject the temperature parameter, so it is only set for OpenAI / Ollama.
        String model = options.build().getModel();
        if (model == null || !model.startsWith("claude-")) {
            options.temperature(0.0);
        }
        return ChatClient.builder(chatModel).defaultOptions(options);
    }

    /**
     * Read-only view of the vector table filled by the ingestion service. The table is created by
     * Flyway (it has an extra full-text column), so Spring AI must not create it.
     */
    @Bean
    @Qualifier("ragVectorStore")
    VectorStore ragVectorStore(JdbcTemplate jdbcTemplate, EmbeddingModel embeddingModel, RagConfigData rag) {
        return PgVectorStore.builder(jdbcTemplate, embeddingModel)
                .schemaName("ingestion")
                .vectorTableName("rag_vector_store")
                .dimensions(rag.embeddingDimensions())
                .distanceType(PgVectorStore.PgDistanceType.COSINE_DISTANCE)
                .indexType(PgVectorStore.PgIndexType.HNSW)
                .initializeSchema(false)
                .build();
    }

    @Bean
    RetrievalAugmentationAdvisor ragAdvisor(DomainSynonymTransformer synonymTransformer,
                                            HybridDocumentRetriever hybridRetriever,
                                            CrossEncoderRerankPostProcessor rerankProcessor,
                                            ParentContextPostProcessor parentContextProcessor,
                                            ContextCompressionPostProcessor compressionProcessor,
                                            CitationHeaderPostProcessor citationProcessor,
                                            @Qualifier("utilityChatClientBuilder") ChatClient.Builder expanderBuilder,
                                            RagConfigData rag,
                                            @Value("classpath:templates/query-expander-prompt.st") Resource expanderPrompt,
                                            @Value("classpath:templates/rag-context-prompt.st") Resource contextPrompt,
                                            @Value("classpath:templates/rag-empty-context-prompt.st") Resource emptyPrompt)
            throws IOException {

        String expanderTemplate = expanderPrompt.getContentAsString(StandardCharsets.UTF_8);
        MultiQueryExpander multiQueryExpander = MultiQueryExpander.builder()
                .chatClientBuilder(expanderBuilder.clone())
                .numberOfQueries(rag.queryExpander().numberOfQueries())
                .includeOriginal(true)
                .promptTemplate(new PromptTemplate(expanderTemplate))
                .build();

        return RetrievalAugmentationAdvisor.builder()
                // Step 1: clean the question and expand domain abbreviations
                .queryTransformers(synonymTransformer)
                // Step 2: 3 paraphrases + the original question
                .queryExpander(new SafeMultiQueryExpander(multiQueryExpander, expanderBuilder.clone().build(),
                        expanderTemplate, rag.queryExpander().numberOfQueries()))
                // Step 3: hybrid search (semantic + keyword), always filtered by the user's tenant
                .documentRetriever(hybridRetriever)
                // Step 4: rerank → parent sections → keep only relevant sentences → add [S1] citation headers
                .documentPostProcessors(rerankProcessor, parentContextProcessor, compressionProcessor, citationProcessor)
                // Step 5: build the final prompt; with no context the model must answer "I don't know"
                .queryAugmenter(ContextualQueryAugmenter.builder()
                        .allowEmptyContext(false)
                        .promptTemplate(new PromptTemplate(contextPrompt))
                        .emptyContextPromptTemplate(new PromptTemplate(emptyPrompt))
                        .documentFormatter(docs -> docs.stream().map(Document::getText).collect(Collectors.joining("\n\n---\n\n")))
                        .build())
                .build();
    }

    /** The Q&amp;A chat client (course: openAiAdvancedRagChatClient). */
    @Bean
    @Qualifier("complianceChatClient")
    ChatClient complianceChatClient(ChatModel chatModel, RetrievalAugmentationAdvisor ragAdvisor) {
        return ChatClient.builder(chatModel).defaultAdvisors(ragAdvisor).build();
    }
}
