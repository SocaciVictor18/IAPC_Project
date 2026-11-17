package com.edupulse.compliance.web;

import com.edupulse.common.security.TenantContext;
import com.edupulse.compliance.qa.AskRequest;
import com.edupulse.compliance.qa.AskResponse;
import com.edupulse.compliance.qa.ComplianceQaService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;

/** Q&amp;A over the internal documents (course: InternalQAController, POST /api/internal-qa/ask). */
@RestController
@RequestMapping("/api/compliance")
@RequiredArgsConstructor
public class ComplianceQaController {

    private final ComplianceQaService qa;

    @PostMapping("/ask")
    public AskResponse ask(@Valid @RequestBody AskRequest request) {
        return qa.ask(request, TenantContext.current());
    }

    /**
     * Same as /ask but the answer is streamed (Server-Sent Events) so the user sees the first words
     * quickly: events "meta" → "token"… → "citations" → "done".
     */
    @PostMapping(value = "/ask/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@Valid @RequestBody AskRequest request) {
        ComplianceQaService.Request prepared = qa.prepare(request, TenantContext.current());
        SseEmitter emitter = new SseEmitter(180_000L);
        Thread.ofVirtual().start(() -> {
            try {
                emitter.send(SseEmitter.event().name("meta").data(Map.of("conversationId", prepared.conversationId())));
                AskResponse response = qa.stream(prepared, token -> {
                    try {
                        emitter.send(SseEmitter.event().name("token").data(Map.of("text", token)));
                    } catch (IOException e) {
                        throw new IllegalStateException("Client disconnected", e);
                    }
                });
                emitter.send(SseEmitter.event().name("citations").data(response.citations()));
                emitter.send(SseEmitter.event().name("done").data(Map.of("auditId", response.auditId(),
                        "outcome", response.outcome(), "latencyMs", response.latencyMs(),
                        "expandedQueries", response.expandedQueries())));
                emitter.complete();
            } catch (Exception e) {
                emitter.completeWithError(e);
            }
        });
        return emitter;
    }
}
