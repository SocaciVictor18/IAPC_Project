package com.edupulse.compliance.config;

import com.edupulse.compliance.rag.guardrails.TopicBoundaryAdvisor;
import com.edupulse.compliance.rag.postprocessing.CitationHeaderPostProcessor;
import com.edupulse.compliance.rag.postprocessing.ContextCompressionPostProcessor;
import com.edupulse.compliance.rag.postprocessing.CrossEncoderRerankPostProcessor;
import com.edupulse.compliance.rag.postprocessing.NeighborStitchPostProcessor;
import com.edupulse.compliance.rag.postprocessing.ParentContextPostProcessor;
import com.edupulse.compliance.rag.preprocessing.DomainSynonymTransformer;
import com.edupulse.compliance.rag.preprocessing.FollowUpCompressionTransformer;
import com.edupulse.compliance.rag.preprocessing.TracingQueryExpander;
import com.edupulse.compliance.rag.retrieval.HybridDocumentRetriever;
import com.edupulse.compliance.rag.retrieval.RrfDocumentJoiner;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.SafeGuardAdvisor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.rag.generation.augmentation.ContextualQueryAugmenter;
import org.springframework.ai.rag.postretrieval.document.DocumentPostProcessor;
import org.springframework.ai.rag.preretrieval.query.expansion.MultiQueryExpander;
import org.springframework.ai.rag.preretrieval.query.transformation.CompressionQueryTransformer;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.core.task.VirtualThreadTaskExecutor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Wiring of the Advanced RAG pipeline (successor of the course's {@code AIProviderConfig}).
 * Provider selection uses {@code spring.ai.model.chat / spring.ai.model.embedding} instead of the
 * course's {@code @Primary} delegate bean, so the same code runs on OpenAI or Ollama.
 * <pre>
 * SafeGuardAdvisor → TopicBoundaryAdvisor → RetrievalAugmentationAdvisor
 *   pre:   FollowUpCompression (only with history) → DomainSynonymTransformer → MultiQueryExpander (3 + original)
 *   ret.:  HybridDocumentRetriever (pgvector + tsvector, RRF, tenant filter) per query, in parallel → RRF joiner
 *   post:  CrossEncoderRerank (top 20 → 8) → [NeighborStitch] → ParentContext → ContextCompression → CitationHeader
 *   gen.:  ContextualQueryAugmenter(allowEmptyContext=false)
 * </pre>
 */
@Configuration(proxyBeanMethods = false)
public class AiConfig {

    /** Small, deterministic model for query expansion, classification, compression and gap-analysis steps. */
    @Bean
    @Qualifier("utilityChatClientBuilder")
    ChatClient.Builder utilityChatClientBuilder(ChatModel chatModel, RagProperties props) {
        ChatOptions.Builder<?> options = chatModel.getDefaultOptions().mutate().maxTokens(1024);
        String model = props.utilityModel() != null && !props.utilityModel().isBlank()
                ? props.utilityModel() : chatModel.getDefaultOptions().getModel();
        if (props.utilityModel() != null && !props.utilityModel().isBlank()) {
            options.model(props.utilityModel());
        }
        // Deterministic (temperature 0) on OpenAI/Ollama. Current Claude models (Opus 5.5, Haiku 5.5) reject
        // sampling parameters with HTTP 400, so it is omitted there.
        if (model == null || !model.startsWith("claude-")) {
            options.temperature(0.0);
        }
        return ChatClient.builder(chatModel).defaultOptions(options);
    }

    /** Read-only view of the vector table written by the ingestion service (compliance_app has SELECT only). */
    @Bean
    @Qualifier("ragVectorStore")
    VectorStore ragVectorStore(JdbcTemplate jdbcTemplate, EmbeddingModel embeddingModel, RagProperties props) {
        return PgVectorStore.builder(jdbcTemplate, embeddingModel)
                .schemaName("ingestion")
                .vectorTableName("rag_vector_store")
                .dimensions(props.embeddingDimensions())
                .distanceType(PgVectorStore.PgDistanceType.COSINE_DISTANCE)
                .indexType(PgVectorStore.PgIndexType.HNSW)
                .initializeSchema(false)
                .build();
    }

    @Bean
    RetrievalAugmentationAdvisor ragAdvisor(HybridDocumentRetriever retriever,
                                            DomainSynonymTransformer synonyms,
                                            @Qualifier("utilityChatClientBuilder") ChatClient.Builder utility,
                                            CrossEncoderRerankPostProcessor rerank,
                                            NeighborStitchPostProcessor neighbors,
                                            ParentContextPostProcessor parent,
                                            ContextCompressionPostProcessor compress,
                                            CitationHeaderPostProcessor cite,
                                            RagProperties props,
                                            MeterRegistry meters,
                                            @Value("classpath:templates/query-expander-prompt.st") Resource expanderPrompt,
                                            @Value("classpath:templates/rag-context-prompt.st") Resource contextPrompt,
                                            @Value("classpath:templates/rag-empty-context-prompt.st") Resource emptyPrompt) {

        List<DocumentPostProcessor> post = new ArrayList<>();
        post.add(rerank);
        if (props.neighborStitch().enabled()) {
            post.add(neighbors);
        }
        if (props.parentContext().enabled()) {
            post.add(parent);
        }
        post.add(compress);
        post.add(cite);

        RetrievalAugmentationAdvisor.Builder builder = RetrievalAugmentationAdvisor.builder()
                .queryTransformers(
                        new FollowUpCompressionTransformer(CompressionQueryTransformer.builder().chatClientBuilder(utility.clone()).build()),
                        synonyms)
                .documentRetriever(retriever)
                .documentJoiner(new RrfDocumentJoiner(props))
                .documentPostProcessors(post)
                .queryAugmenter(ContextualQueryAugmenter.builder()
                        .allowEmptyContext(false)
                        .promptTemplate(new PromptTemplate(contextPrompt))
                        .emptyContextPromptTemplate(new PromptTemplate(emptyPrompt))
                        .documentFormatter(docs -> docs.stream().map(Document::getText).collect(Collectors.joining("\n\n---\n\n")))
                        .build())
                // retrieval for the expanded queries runs in parallel on virtual threads
                .taskExecutor(new VirtualThreadTaskExecutor("rag-"));

        if (props.queryExpander().enabled()) {
            MultiQueryExpander expander = MultiQueryExpander.builder()
                    .chatClientBuilder(utility.clone())
                    .numberOfQueries(props.queryExpander().numberOfQueries())
                    .includeOriginal(props.queryExpander().includeOriginal())
                    .promptTemplate(new PromptTemplate(expanderPrompt))
                    .build();
            String template;
            try {
                template = expanderPrompt.getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
            } catch (java.io.IOException e) {
                throw new IllegalStateException(e);
            }
            builder.queryExpander(new TracingQueryExpander(expander, props.queryExpander().cacheSize(), meters,
                    utility.clone().build(), template, props.queryExpander().numberOfQueries()));
        }
        return builder.build();
    }

    @Bean
    SafeGuardAdvisor safeGuardAdvisor(RagProperties props) {
        return SafeGuardAdvisor.builder()
                .sensitiveWords(props.safeguard().sensitiveWords())
                .failureResponse(props.safeguard().failureResponse())
                .order(-200)
                .build();
    }

    @Bean
    TopicBoundaryAdvisor topicBoundaryAdvisor(EmbeddingModel embeddingModel,
                                              @Qualifier("utilityChatClientBuilder") ChatClient.Builder utility,
                                              RagProperties props) {
        return new TopicBoundaryAdvisor(embeddingModel, utility.clone(), props.topicBoundary());
    }

    /** The main Q&amp;A client (course: {@code openAiAdvancedRagChatClient}). */
    @Bean
    @Qualifier("complianceChatClient")
    ChatClient complianceChatClient(ChatModel chatModel, SafeGuardAdvisor safeGuard, TopicBoundaryAdvisor topic,
                                    RetrievalAugmentationAdvisor rag) {
        return ChatClient.builder(chatModel).defaultAdvisors(safeGuard, topic, rag).build();
    }
}
