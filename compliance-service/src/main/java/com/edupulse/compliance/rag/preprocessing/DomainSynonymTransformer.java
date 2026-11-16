package com.edupulse.compliance.rag.preprocessing;

import com.edupulse.compliance.config.RagProperties;
import com.edupulse.compliance.rag.RagContextKeys;
import com.edupulse.compliance.rag.RagTrace;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.preretrieval.query.transformation.QueryTransformer;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Course component, migrated to Spring AI 2.0:
 * <ol>
 *   <li>strips conversational noise ("please", "can you", trailing "?"),</li>
 *   <li>expands domain acronyms (MFA, SSO, DPO…) into the wording used by the documents.</li>
 * </ol>
 * Patterns are compiled once in the constructor, in configuration order (LinkedHashMap), with word
 * boundaries, {@link Pattern#quote} and case-insensitivity.
 * <p>
 * Fixes vs. the course notes: correct packages ({@code org.springframework.ai.rag.*}) and
 * {@code query.mutate()} instead of a non-existent {@code QueryBuilder}, so the query history and
 * context (tenant!) are preserved.
 */
@Slf4j
@Component
public class DomainSynonymTransformer implements QueryTransformer {

    private static final Pattern POLITE_PREFIX = Pattern.compile(
            "^(?:(?:please|pls|can you|could you|would you|tell me|i want to know)[,\\s]*)+", Pattern.CASE_INSENSITIVE);
    private static final Pattern TRAILING_QUESTION = Pattern.compile("\\s*\\?+\\s*$");

    private final Map<Pattern, String> replacements = new LinkedHashMap<>();

    public DomainSynonymTransformer(RagProperties props) {
        props.synonymsOrEmpty().forEach((key, value) -> replacements.put(
                Pattern.compile("\\b" + Pattern.quote(key) + "\\b", Pattern.CASE_INSENSITIVE),
                Matcher.quoteReplacement(value)));
    }

    @Override
    public Query transform(Query query) {
        String text = query.text();
        if (!StringUtils.hasText(text)) {
            return query;
        }
        String transformed = applyDomainSynonyms(clean(text));
        if (transformed.isBlank()) {
            transformed = text;
        }
        log.info("Transformed query from: '{}' to: '{}'", text, transformed);
        if (query.context().get(RagContextKeys.TRACE) instanceof RagTrace trace) {
            trace.transformedQuery(transformed);
        }
        return query.mutate().text(transformed).build();
    }

    String clean(String text) {
        String withoutPrefix = POLITE_PREFIX.matcher(text.strip()).replaceFirst("");
        return TRAILING_QUESTION.matcher(withoutPrefix).replaceAll("").strip();
    }

    String applyDomainSynonyms(String text) {
        String updated = text;
        for (Map.Entry<Pattern, String> e : replacements.entrySet()) {
            updated = e.getKey().matcher(updated).replaceAll(e.getValue());
        }
        return updated;
    }
}
