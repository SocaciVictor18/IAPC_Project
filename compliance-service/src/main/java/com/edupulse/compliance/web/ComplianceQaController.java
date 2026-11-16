package com.edupulse.compliance.web;

import com.edupulse.common.security.TenantContext;
import com.edupulse.compliance.qa.AskRequest;
import com.edupulse.compliance.qa.AskResponse;
import com.edupulse.compliance.qa.ComplianceQaService;
import com.edupulse.compliance.qa.ComplianceQaService.Prepared;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;

/** Successor of the course's {@code InternalQAController} ({@code POST /api/internal-qa/ask}). */
@Slf4j
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
     * Server-Sent Events: {@code meta} (conversationId) → {@code token}* → {@code citations} → {@code done}.
     * Retrieval + generation run on a virtual thread; nginx and the gateway pass the stream unbuffered.
     */
    @PostMapping(value = "/ask/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@Valid @RequestBody AskRequest request) {
        Prepared prepared = qa.prepare(request, TenantContext.current());
        SseEmitter emitter = new SseEmitter(180_000L);
        Thread.ofVirtual().name("sse-ask").start(() -> {
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
                emitter.send(SseEmitter.event().name("done").data(Map.of(
                        "auditId", response.auditId(),
                        "outcome", response.outcome(),
                        "ungrounded", response.ungrounded(),
                        "latencyMs", response.latencyMs(),
                        "retrieval", response.retrieval())));
                emitter.complete();
            } catch (Exception e) {
                log.warn("SSE stream ended with error: {}", e.getMessage());
                try {
                    emitter.send(SseEmitter.event().name("error").data(Map.of("message", String.valueOf(e.getMessage()))));
                } catch (Exception ignored) {
                    // client already gone
                }
                emitter.completeWithError(e);
            }
        });
        return emitter;
    }
}
