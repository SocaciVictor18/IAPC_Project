package com.edupulse.compliance.rag.preprocessing;

import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.preretrieval.query.transformation.CompressionQueryTransformer;
import org.springframework.ai.rag.preretrieval.query.transformation.QueryTransformer;

/**
 * Wraps Spring AI's {@link CompressionQueryTransformer}, which rewrites "conversation history +
 * follow-up" into a standalone question (e.g. "and for contractors?" → "What are the password rules
 * for contractors?"). It is only invoked when there is history, saving an LLM call on first questions.
 * <p>
 * Note: despite the name, this does NOT compress retrieved context – that is
 * {@code ContextCompressionPostProcessor}.
 */
public class FollowUpCompressionTransformer implements QueryTransformer {

    private final CompressionQueryTransformer delegate;

    public FollowUpCompressionTransformer(CompressionQueryTransformer delegate) {
        this.delegate = delegate;
    }

    @Override
    public Query transform(Query query) {
        boolean hasConversation = query.history().stream()
                .anyMatch(m -> m.getMessageType() == org.springframework.ai.chat.messages.MessageType.ASSISTANT);
        return hasConversation ? delegate.transform(query) : query;
    }
}
