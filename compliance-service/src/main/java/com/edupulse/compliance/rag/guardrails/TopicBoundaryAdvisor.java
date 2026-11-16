package com.edupulse.compliance.rag.guardrails;

import com.edupulse.compliance.audit.Outcome;
import com.edupulse.compliance.config.RagProperties;
import com.edupulse.compliance.rag.RagContextKeys;
import com.edupulse.compliance.rag.RagTrace;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.core.io.ClassPathResource;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Custom guardrail (Spring AI has no TopicBoundaryAdvisor): refuses questions unrelated to
 * corporate compliance, so the bot does not hand out advice outside its mandate.
 * <ol>
 *   <li>Fast path: cosine similarity between the question and ~25 pre-embedded topic descriptions;
 *       max ≥ accept-threshold → in scope, no LLM call.</li>
 *   <li>Grey zone (grey-zone-min ≤ max &lt; accept): a small LLM call with structured output decides.</li>
 *   <li>Below grey-zone-min (and no conversation in progress): out of scope → the chain is
 *       short-circuited with a standard refusal and {@link Outcome#OUT_OF_SCOPE} is recorded.</li>
 * </ol>
 * Implements both call and stream, ordered between SafeGuardAdvisor and the RAG advisor.
 */
@Slf4j
public class TopicBoundaryAdvisor implements CallAdvisor, StreamAdvisor {

    public static final int ORDER = -100;

    private final EmbeddingModel embeddingModel;
    private final ChatClient.Builder utility;
    private final RagProperties.TopicBoundary props;
    private final List<String> topics;
    private volatile List<float[]> topicVectors;

    public TopicBoundaryAdvisor(EmbeddingModel embeddingModel, ChatClient.Builder utility, RagProperties.TopicBoundary props) {
        this.embeddingModel = embeddingModel;
        this.utility = utility;
        this.props = props;
        this.topics = loadTopics();
    }

    private static final java.util.regex.Pattern OUT_WORD = java.util.regex.Pattern.compile("\\bOUT\\b", java.util.regex.Pattern.CASE_INSENSITIVE);
    private static final java.util.regex.Pattern IN_WORD = java.util.regex.Pattern.compile("^\\W*IN\\b", java.util.regex.Pattern.CASE_INSENSITIVE);

    public record Verdict(boolean inScope, String reason) {
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        Verdict v = classify(request);
        if (!v.inScope()) {
            return refuse(request, v);
        }
        return chain.nextCall(request);
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        return Flux.defer(() -> {
            Verdict v = classify(request);
            return v.inScope() ? chain.nextStream(request) : Flux.just(refuse(request, v));
        }).subscribeOn(Schedulers.boundedElastic());
    }

    public Verdict classify(ChatClientRequest request) {
        if (!props.enabled()) {
            return new Verdict(true, "disabled");
        }
        String question = request.prompt().getUserMessage().getText();
        boolean inConversation = request.prompt().getInstructions().stream()
                .anyMatch(m -> m.getMessageType() == MessageType.ASSISTANT);
        try {
            double score = maxSimilarity(question);
            if (score >= props.acceptThreshold()) {
                return new Verdict(true, "topic similarity " + round(score));
            }
            if (score < props.greyZoneMin() && !inConversation) {
                return new Verdict(false, "topic similarity " + round(score));
            }
            // Plain one-word verdict instead of JSON structured output: robust even on small local models.
            String verdict = utility.clone().build().prompt()
                    .system("""
                            You are a strict classifier for an internal corporate compliance assistant.
                            IN scope: information security, data protection/privacy (GDPR), regulations, internal policies,
                            audits, risk, incident response, access control, employee security duties, finance/HR/vendor policies,
                            and follow-up questions about those topics.
                            OUT of scope: anything else (recipes, cooking, sports, entertainment, travel tips, general trivia,
                            homework, coding help, personal advice).
                            Reply with exactly one word: IN or OUT.
                            """)
                    .user("Question: " + question)
                    .call()
                    .content();
            boolean out = verdict != null && OUT_WORD.matcher(verdict).find() && !IN_WORD.matcher(verdict).find();
            return new Verdict(!out, "llm: " + (verdict == null ? "no verdict" : verdict.strip()) + " (similarity " + round(score) + ")");
        } catch (Exception e) {
            // Availability over strictness: a classifier failure must not block legitimate questions.
            log.warn("Topic classification failed, allowing question: {}", e.getMessage());
            return new Verdict(true, "classifier unavailable");
        }
    }

    private ChatClientResponse refuse(ChatClientRequest request, Verdict v) {
        log.info("Out-of-scope question refused ({})", v.reason());
        if (request.context().get(RagContextKeys.TRACE) instanceof RagTrace trace) {
            trace.force(Outcome.OUT_OF_SCOPE, v.reason());
        }
        ChatResponse response = ChatResponse.builder()
                .generations(List.of(new Generation(new AssistantMessage(props.refusal()))))
                .build();
        return ChatClientResponse.builder().chatResponse(response).context(request.context()).build();
    }

    private double maxSimilarity(String question) {
        List<float[]> vectors = topicVectors;
        if (vectors == null) {
            synchronized (this) {
                if (topicVectors == null) {
                    topicVectors = embeddingModel.embed(topics);
                }
                vectors = topicVectors;
            }
        }
        float[] q = embeddingModel.embed(question);
        double max = -1;
        for (float[] t : vectors) {
            max = Math.max(max, cosine(q, t));
        }
        return max;
    }

    private static double cosine(float[] a, float[] b) {
        double dot = 0;
        double na = 0;
        double nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        return na == 0 || nb == 0 ? 0 : dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    private static String round(double d) {
        return String.format(java.util.Locale.ROOT, "%.2f", d);
    }

    private static List<String> loadTopics() {
        try {
            String text = new ClassPathResource("guardrails/topics.txt").getContentAsString(StandardCharsets.UTF_8);
            return text.lines().map(String::strip).filter(l -> !l.isEmpty() && !l.startsWith("#")).toList();
        } catch (IOException e) {
            throw new IllegalStateException("Cannot load guardrails/topics.txt", e);
        }
    }

    @Override
    public String getName() {
        return "TopicBoundaryAdvisor";
    }

    @Override
    public int getOrder() {
        return ORDER;
    }
}
