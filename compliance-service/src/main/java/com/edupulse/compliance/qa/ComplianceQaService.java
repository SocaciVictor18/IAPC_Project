package com.edupulse.compliance.qa;

import com.edupulse.common.security.TenantContext;
import com.edupulse.common.web.ApiException;
import com.edupulse.compliance.audit.AuditEntry;
import com.edupulse.compliance.audit.AuditService;
import com.edupulse.compliance.audit.Outcome;
import com.edupulse.compliance.config.RagProperties;
import com.edupulse.compliance.qa.AskResponse.Citation;
import com.edupulse.compliance.rag.RagContextKeys;
import com.edupulse.compliance.rag.RagMode;
import com.edupulse.compliance.rag.RagTrace;
import com.edupulse.compliance.rag.RequestFilters;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Orchestrates one compliance question: input validation → guarded RAG call → deterministic
 * citations → audit record. Used by both the blocking and the streaming (SSE) endpoints.
 */
@Slf4j
@Service
public class ComplianceQaService {

    public static final String I_DONT_KNOW = "I don't know based on the available documents.";

    private final ChatClient chatClient;
    private final AuditService audit;
    private final RagProperties props;
    private final MeterRegistry meters;
    private final String systemTemplate;

    public ComplianceQaService(@Qualifier("complianceChatClient") ChatClient chatClient, AuditService audit,
                               RagProperties props, MeterRegistry meters,
                               @Value("classpath:templates/system-prompt.st") Resource systemPrompt) throws IOException {
        this.chatClient = chatClient;
        this.audit = audit;
        this.props = props;
        this.meters = meters;
        this.systemTemplate = systemPrompt.getContentAsString(StandardCharsets.UTF_8);
    }

    /** Prepared request: validated question, conversation, history and the per-request trace. */
    public record Prepared(TenantContext tenant, String question, UUID conversationId, List<Message> history,
                           RequestFilters filters, RagMode mode, RagTrace trace, long startNanos) {
    }

    public Prepared prepare(AskRequest req, TenantContext tenant) {
        String question = sanitize(req.question());
        if (question.isBlank()) {
            throw ApiException.badRequest("question must not be blank");
        }
        if (question.length() > props.input().maxQuestionChars()) {
            throw ApiException.badRequest("question exceeds " + props.input().maxQuestionChars() + " characters");
        }
        UUID conversationId = audit.ensureConversation(req.conversationId(), tenant, question);
        List<Message> history = new ArrayList<>();
        for (AuditService.Turn t : audit.history(conversationId, props.input().historyTurns())) {
            history.add(new UserMessage(t.question()));
            history.add(new AssistantMessage(t.answer()));
        }
        return new Prepared(tenant, question, conversationId, history, RequestFilters.orNone(req.filters()),
                req.mode() == null ? RagMode.FULL : req.mode(), new RagTrace(), System.nanoTime());
    }

    public AskResponse ask(AskRequest req, TenantContext tenant) {
        Prepared p = prepare(req, tenant);
        try {
            ChatClientResponse response = spec(p).call().chatClientResponse();
            String answer = text(response.chatResponse());
            List<Document> context = contextDocuments(response);
            Usage usage = usage(response.chatResponse());
            String model = model(response.chatResponse());
            return finish(p, answer, context, usage, model, null, false);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            log.error("Q&A failed", e);
            recordError(p, e, false);
            throw new ApiException(HttpStatus.BAD_GATEWAY, "The AI provider failed to answer: " + e.getMessage());
        }
    }

    /**
     * Streaming variant: {@code onToken} receives text fragments as they are generated.
     * Returns the final response (citations, audit id) once generation completes.
     */
    public AskResponse stream(Prepared p, Consumer<String> onToken) {
        StringBuilder answer = new StringBuilder();
        List<Document> context = null;
        Usage usage = null;
        String model = null;
        Integer ttft = null;
        try {
            for (ChatClientResponse r : spec(p).stream().chatClientResponse().toIterable()) {
                if (context == null || context.isEmpty()) {
                    List<Document> docs = contextDocuments(r);
                    if (!docs.isEmpty()) {
                        context = docs;
                    }
                }
                String chunk = text(r.chatResponse());
                if (chunk != null && !chunk.isEmpty()) {
                    if (ttft == null) {
                        ttft = (int) ((System.nanoTime() - p.startNanos()) / 1_000_000);
                        meters.timer("rag.ttft").record(java.time.Duration.ofMillis(ttft));
                    }
                    answer.append(chunk);
                    onToken.accept(chunk);
                }
                Usage u = usage(r.chatResponse());
                if (u != null && u.getPromptTokens() != null && u.getPromptTokens() > 0) {
                    usage = u;
                }
                String m = model(r.chatResponse());
                if (m != null) {
                    model = m;
                }
            }
            return finish(p, answer.toString(), context == null ? List.of() : context, usage, model, ttft, true);
        } catch (Exception e) {
            log.error("Streaming Q&A failed", e);
            recordError(p, e, true);
            throw new ApiException(HttpStatus.BAD_GATEWAY, "The AI provider failed to answer: " + e.getMessage());
        }
    }

    private ChatClient.ChatClientRequestSpec spec(Prepared p) {
        String system = systemTemplate.replace("{organizationName}",
                p.tenant().organizationName() == null ? "your organization" : p.tenant().organizationName());
        return chatClient.prompt()
                .system(system)
                .messages(p.history())
                .user(p.question())
                .advisors(a -> a
                        .param(RagContextKeys.TENANT, p.tenant())
                        .param(RagContextKeys.FILTERS, p.filters())
                        .param(RagContextKeys.MODE, p.mode())
                        .param(RagContextKeys.TRACE, p.trace()));
    }

    private AskResponse finish(Prepared p, String answer, List<Document> context, Usage usage, String model,
                               Integer ttft, boolean streamed) {
        Outcome outcome = outcome(p.trace(), answer, context);
        boolean ungrounded = outcome == Outcome.ANSWERED && CitationExtractor.labelsIn(answer).isEmpty()
                && !answer.contains("I don't know");
        List<Citation> citations = outcome == Outcome.ANSWERED || outcome == Outcome.NO_CONTEXT
                ? CitationExtractor.citations(context, answer) : List.of();
        long latency = (System.nanoTime() - p.startNanos()) / 1_000_000;
        UUID auditId = audit.record(entry(p, answer, model, usage, (int) latency, ttft, outcome, ungrounded, streamed,
                CitationExtractor.auditChunks(context, answer)));
        meters.counter("rag.answers", "outcome", outcome.name()).increment();
        meters.timer("rag.ask.latency", "streamed", String.valueOf(streamed)).record(java.time.Duration.ofMillis(latency));
        return new AskResponse(p.conversationId(), auditId, answer, outcome, ungrounded, citations,
                new AskResponse.Usage(usage == null ? null : usage.getPromptTokens(),
                        usage == null ? null : usage.getCompletionTokens(), model),
                latency,
                new AskResponse.Retrieval(p.trace().transformedQuery(), p.trace().expandedQueries(), p.trace().candidates(),
                        p.trace().steps()));
    }

    private Outcome outcome(RagTrace trace, String answer, List<Document> context) {
        if (trace.forcedOutcome() != null) {
            return trace.forcedOutcome();
        }
        if (answer != null && answer.strip().equals(props.safeguard().failureResponse().strip())) {
            return Outcome.BLOCKED_SAFEGUARD;
        }
        if (context.isEmpty() || (answer != null && answer.contains("I don't know based on the available documents"))) {
            return Outcome.NO_CONTEXT;
        }
        return Outcome.ANSWERED;
    }

    private void recordError(Prepared p, Exception e, boolean streamed) {
        try {
            long latency = (System.nanoTime() - p.startNanos()) / 1_000_000;
            audit.record(entry(p, "ERROR: " + e.getClass().getSimpleName() + ": " + e.getMessage(), null, null,
                    (int) latency, null, Outcome.ERROR, false, streamed, List.of()));
        } catch (Exception auditFailure) {
            log.error("Could not write audit record for failed request", auditFailure);
        }
    }

    private AuditEntry entry(Prepared p, String answer, String model, Usage usage, int latency, Integer ttft,
                             Outcome outcome, boolean ungrounded, boolean streamed, List<AuditEntry.Chunk> chunks) {
        TenantContext t = p.tenant();
        return new AuditEntry(p.conversationId(), t.userId(), t.email(), t.organizationId(), t.orgUnitId(),
                String.join(",", t.roles()), p.question(), p.trace().transformedQuery(), p.trace().expandedQueries(),
                p.trace().appliedFilter(), answer, model, usage == null ? null : usage.getPromptTokens(),
                usage == null ? null : usage.getCompletionTokens(), latency, ttft, outcome, ungrounded, streamed, chunks);
    }

    @SuppressWarnings("unchecked")
    static List<Document> contextDocuments(ChatClientResponse r) {
        Object docs = r.context() == null ? null : r.context().get(RetrievalAugmentationAdvisor.DOCUMENT_CONTEXT);
        if (docs == null && r.chatResponse() != null && r.chatResponse().getMetadata() != null) {
            docs = r.chatResponse().getMetadata().get(RetrievalAugmentationAdvisor.DOCUMENT_CONTEXT);
        }
        return docs instanceof List<?> l ? (List<Document>) l : List.of();
    }

    private static String text(ChatResponse r) {
        if (r == null || r.getResult() == null || r.getResult().getOutput() == null) {
            return "";
        }
        String t = r.getResult().getOutput().getText();
        return t == null ? "" : t;
    }

    private static Usage usage(ChatResponse r) {
        ChatResponseMetadata md = r == null ? null : r.getMetadata();
        return md == null ? null : md.getUsage();
    }

    private static String model(ChatResponse r) {
        ChatResponseMetadata md = r == null ? null : r.getMetadata();
        return md == null || md.getModel() == null || md.getModel().isBlank() ? null : md.getModel();
    }

    /** Removes control characters (except newlines/tabs) and trims. */
    static String sanitize(String s) {
        return s == null ? "" : s.replaceAll("[\\p{Cntrl}&&[^\\n\\t]]", "").strip();
    }
}
