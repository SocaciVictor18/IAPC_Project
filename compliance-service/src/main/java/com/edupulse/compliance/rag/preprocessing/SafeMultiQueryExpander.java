package com.edupulse.compliance.rag.preprocessing;

import com.edupulse.compliance.rag.RagContextKeys;
import com.edupulse.compliance.rag.RagTrace;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.preretrieval.query.expansion.MultiQueryExpander;
import org.springframework.ai.rag.preretrieval.query.expansion.QueryExpander;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Wraps Spring AI's {@link MultiQueryExpander} (3 variants + the original question).
 * MultiQueryExpander only accepts an answer with exactly 3 clean lines; small local models (llama3.2)
 * often add numbering or an intro line, and then it silently returns only the original. In that case
 * we ask again with the same template and clean the lines ourselves. The variants are saved for the audit log.
 */
@Slf4j
public class SafeMultiQueryExpander implements QueryExpander {

    private final MultiQueryExpander delegate;
    private final ChatClient chatClient;
    private final String promptTemplate;
    private final int numberOfQueries;

    public SafeMultiQueryExpander(MultiQueryExpander delegate, ChatClient chatClient, String promptTemplate, int numberOfQueries) {
        this.delegate = delegate;
        this.chatClient = chatClient;
        this.promptTemplate = promptTemplate;
        this.numberOfQueries = numberOfQueries;
    }

    @Override
    public List<Query> expand(Query query) {
        List<String> texts;
        try {
            texts = delegate.expand(query).stream().map(Query::text).distinct().toList();
            if (texts.size() <= 1) {
                texts = expandLeniently(query.text());
            }
        } catch (Exception e) {
            log.warn("Query expansion failed, using only the original question: {}", e.getMessage());
            texts = List.of(query.text());
        }
        log.info("Expanded into {} queries: {}", texts.size(), texts);
        if (query.context().get(RagContextKeys.TRACE) instanceof RagTrace trace) {
            trace.expandedQueries(texts);
        }
        return texts.stream().map(t -> query.mutate().text(t).build()).toList();
    }

    private List<String> expandLeniently(String original) {
        String prompt = promptTemplate.replace("{number}", String.valueOf(numberOfQueries)).replace("{query}", original);
        List<String> result = new ArrayList<>();
        result.add(original);
        result.addAll(parseVariants(chatClient.prompt().user(prompt).call().content(), original, numberOfQueries));
        return result;
    }

    /** Removes numbering, bullets, quotes and intro lines such as "Here are 3 paraphrases:". */
    static List<String> parseVariants(String output, String original, int max) {
        List<String> variants = new ArrayList<>();
        if (output == null) {
            return variants;
        }
        for (String line : output.split("\\R")) {
            String v = line.replaceFirst("^\\s*(\\d+[.):]|[-*•])\\s*", "").replaceAll("^[\"'*]+|[\"'*]+$", "").strip();
            String lower = v.toLowerCase(Locale.ROOT);
            boolean noise = v.isEmpty() || v.endsWith(":") || lower.startsWith("here ") || lower.startsWith("sure")
                    || v.equalsIgnoreCase(original);
            if (!noise && variants.stream().noneMatch(x -> x.equalsIgnoreCase(v))) {
                variants.add(v);
            }
            if (variants.size() == max) {
                break;
            }
        }
        return variants;
    }
}
