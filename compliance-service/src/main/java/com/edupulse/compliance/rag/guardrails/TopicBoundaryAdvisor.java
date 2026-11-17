package com.edupulse.compliance.rag.guardrails;

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
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Guardrail that keeps the assistant on topic (Spring AI has no TopicBoundaryAdvisor, so it is custom).
 * Step 1: a question that mentions a compliance word (password, patient, incident, billing…) is accepted at once.
 * Step 2: otherwise a small LLM call decides IN or OUT. Out-of-scope questions (recipes, sports…) get a
 * standard refusal and never reach the RAG pipeline.
 */
@Slf4j
public class TopicBoundaryAdvisor implements CallAdvisor, StreamAdvisor {

    /** Words that clearly belong to the hospital's compliance topics (matched at the start of a word). */
    private static final Pattern COMPLIANCE_WORDS = Pattern.compile("\\b(secur|password|mfa|authenticat|account|access|"
            + "patient|health|ehr|phi|record|data|privacy|gdpr|hipaa|polic|regulat|complian|audit|incident|report|breach|"
            + "laptop|device|email|phishing|backup|encrypt|claim|billing|invoice|insur|approv|training|employee|staff|"
            + "vendor|supplier|framework|iso|nist|soc|risk|vault|secret|threat)", Pattern.CASE_INSENSITIVE);

    private static final String CLASSIFIER_PROMPT = """
            You decide if a question can be sent to the internal compliance assistant of a hospital.
            Answer IN for anything about work rules at the hospital: security, passwords, MFA, patient data,
            privacy, policies, regulations, audits, incidents, devices, billing, insurance claims, approvals,
            employee duties – and for follow-up questions.
            Answer OUT only if the question is clearly unrelated to work (cooking, sports, movies, travel,
            homework, jokes) or asks for a medical diagnosis.
            Examples:
            How should I handle MFA? -> IN
            Who approves insurance claims above 10,000 EUR? -> IN
            How do I report a lost laptop? -> IN
            Give me a pasta recipe -> OUT
            Who won the football match yesterday? -> OUT
            Reply with exactly one word: IN or OUT.
            """;

    private final ChatClient classifier;
    private final String refusal;

    public TopicBoundaryAdvisor(ChatClient classifier, String refusal) {
        this.classifier = classifier;
        this.refusal = refusal;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        return isInScope(request) ? chain.nextCall(request) : refuse(request);
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        return Flux.defer(() -> isInScope(request) ? chain.nextStream(request) : Flux.just(refuse(request)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    boolean isInScope(ChatClientRequest request) {
        String question = request.prompt().getUserMessage().getText();
        if (mentionsComplianceTopic(question)) {
            return true;
        }
        try {
            String verdict = classifier.prompt().system(CLASSIFIER_PROMPT).user("Question: " + question).call().content();
            return verdict == null || !verdict.strip().toUpperCase().startsWith("OUT");
        } catch (Exception e) {
            log.warn("Topic check failed, allowing the question: {}", e.getMessage());
            return true;
        }
    }

    public static boolean mentionsComplianceTopic(String question) {
        return question != null && COMPLIANCE_WORDS.matcher(question).find();
    }

    private ChatClientResponse refuse(ChatClientRequest request) {
        log.info("Out-of-scope question refused");
        if (request.context().get(RagContextKeys.TRACE) instanceof RagTrace trace) {
            trace.markOutOfScope();
        }
        ChatResponse response = ChatResponse.builder()
                .generations(List.of(new Generation(new AssistantMessage(refusal))))
                .build();
        return ChatClientResponse.builder().chatResponse(response).context(request.context()).build();
    }

    @Override
    public String getName() {
        return "TopicBoundaryAdvisor";
    }

    @Override
    public int getOrder() {
        return -100;   // after SafeGuardAdvisor (-200), before the RAG advisor (0)
    }
}
