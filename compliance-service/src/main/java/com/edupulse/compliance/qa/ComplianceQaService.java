package com.edupulse.compliance.qa;

import com.edupulse.common.security.TenantContext;
import com.edupulse.common.web.ApiException;
import com.edupulse.compliance.audit.AuditEntry;
import com.edupulse.compliance.audit.AuditService;
import com.edupulse.compliance.audit.Outcome;
import com.edupulse.compliance.config.RagConfigData;
import com.edupulse.compliance.rag.RagContextKeys;
import com.edupulse.compliance.rag.RagTrace;
import com.edupulse.compliance.rag.RequestFilters;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
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

/**
 * One compliance question: call the chat client (RAG advisor) → build the citations → write the audit record.
 */
@Slf4j
@Service
public class ComplianceQaService {

    private final ChatClient chatClient;
    private final AuditService audit;
    private final RagConfigData rag;
    private final String systemPrompt;

    public ComplianceQaService(@Qualifier("complianceChatClient") ChatClient chatClient, AuditService audit,
                               RagConfigData rag, @Value("classpath:templates/system-prompt.st") Resource systemPrompt)
            throws IOException {
        this.chatClient = chatClient;
        this.audit = audit;
        this.rag = rag;
        this.systemPrompt = systemPrompt.getContentAsString(StandardCharsets.UTF_8);
    }

    /** Everything needed for one question. */
    public record Request(TenantContext tenant, String question, UUID conversationId, List<Message> history,
                          RequestFilters filters, RagTrace trace, long startNanos) {
    }

    public Request prepare(AskRequest req, TenantContext tenant) {
        String question = req.question().strip();
        UUID conversationId = audit.ensureConversation(req.conversationId(), tenant, question);
        // the last answered questions of the conversation are sent as chat history (multi-turn)
        List<Message> history = new ArrayList<>();
        for (AuditService.Turn t : audit.history(conversationId, 3)) {
            history.add(new UserMessage(t.question()));
            history.add(new AssistantMessage(t.answer()));
        }
        return new Request(tenant, question, conversationId, history, RequestFilters.orNone(req.filters()),
                new RagTrace(), System.nanoTime());
    }

    public AskResponse ask(AskRequest req, TenantContext tenant) {
        Request r = prepare(req, tenant);
        try {
            ChatClientResponse response = prompt(r).call().chatClientResponse();
            return finish(r, text(response.chatResponse()), contextDocuments(response));
        } catch (Exception e) {
            throw failed(r, e);
        }
    }

    private ChatClient.ChatClientRequestSpec prompt(Request r) {
        String organization = r.tenant().organizationName() == null ? "the hospital" : r.tenant().organizationName();
        return chatClient.prompt()
                .system(systemPrompt.replace("{organizationName}", organization))
                .messages(r.history())
                .user(r.question())
                // the tenant travels with the question to the retriever (filter by org unit + clearance)
                .advisors(a -> a.param(RagContextKeys.TENANT, r.tenant())
                        .param(RagContextKeys.FILTERS, r.filters())
                        .param(RagContextKeys.TRACE, r.trace()));
    }

    private AskResponse finish(Request r, String answer, List<Document> context) {
        Outcome outcome = outcome(r.trace(), answer, context);
        long latency = (System.nanoTime() - r.startNanos()) / 1_000_000;
        UUID auditId = audit.record(entry(r, answer, outcome, latency, CitationExtractor.auditChunks(context, answer)));
        List<AskResponse.Citation> citations = outcome == Outcome.ANSWERED ? CitationExtractor.citations(context, answer) : List.of();
        return new AskResponse(r.conversationId(), auditId, answer, outcome, citations, r.trace().expandedQueries(), latency);
    }

    private Outcome outcome(RagTrace trace, String answer, List<Document> context) {
        if (context.isEmpty() || answer.contains("I don't know based on the available documents")) {
            return Outcome.NO_CONTEXT;
        }
        return Outcome.ANSWERED;
    }

    private ApiException failed(Request r, Exception e) {
        log.error("Q&A failed", e);
        long latency = (System.nanoTime() - r.startNanos()) / 1_000_000;
        audit.record(entry(r, "ERROR: " + e.getMessage(), Outcome.ERROR, latency, List.of()));
        return new ApiException(HttpStatus.BAD_GATEWAY, "The AI provider failed to answer: " + e.getMessage());
    }

    private AuditEntry entry(Request r, String answer, Outcome outcome, long latency, List<AuditEntry.Chunk> chunks) {
        TenantContext t = r.tenant();
        return new AuditEntry(r.conversationId(), t.userId(), t.email(), t.organizationId(), t.orgUnitId(),
                String.join(",", t.roles()), r.question(), r.trace().transformedQuery(), r.trace().expandedQueries(),
                r.trace().appliedFilter(), answer, (int) latency, outcome, chunks);
    }

    /** The documents the RAG advisor put into the prompt. */
    @SuppressWarnings("unchecked")
    static List<Document> contextDocuments(ChatClientResponse r) {
        Object docs = r.context() == null ? null : r.context().get(RetrievalAugmentationAdvisor.DOCUMENT_CONTEXT);
        return docs instanceof List<?> list ? (List<Document>) list : List.of();
    }

    private static String text(ChatResponse r) {
        if (r == null || r.getResult() == null || r.getResult().getOutput() == null) {
            return "";
        }
        String t = r.getResult().getOutput().getText();
        return t == null ? "" : t;
    }
}
