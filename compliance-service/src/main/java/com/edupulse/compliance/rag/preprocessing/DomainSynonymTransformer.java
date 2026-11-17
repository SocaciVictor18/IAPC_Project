package com.edupulse.compliance.rag.preprocessing;

import com.edupulse.compliance.config.RagConfigData;
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
 * Pre-processing from the course: (A) strips polite prefixes and the trailing "?",
 * (B) replaces domain abbreviations (MFA, PHI, EHR…) with the words used in the documents.
 * Patterns are compiled once in the constructor: word boundaries, Pattern.quote, case-insensitive.
 * <p>
 * Spring AI 2.0 changes vs. the course notes: package {@code org.springframework.ai.rag.*} and
 * {@code query.mutate()} (there is no QueryBuilder), which also keeps the query context (tenant).
 */
@Slf4j
@Component
public class DomainSynonymTransformer implements QueryTransformer {

    private final Map<Pattern, String> replacements = new LinkedHashMap<>();

    public DomainSynonymTransformer(RagConfigData ragConfigData) {
        if (ragConfigData.synonyms() != null) {
            ragConfigData.synonyms().forEach((key, value) -> replacements.put(
                    Pattern.compile("\\b" + Pattern.quote(key) + "\\b", Pattern.CASE_INSENSITIVE),
                    Matcher.quoteReplacement(value)));
        }
    }

    @Override
    public Query transform(Query query) {
        String queryText = query.text();
        if (!StringUtils.hasText(queryText)) {
            return query;
        }
        String finalText = applyDomainSynonyms(getCleanedText(queryText));
        log.info("Transformed query from: '{}' to: '{}'", queryText, finalText);
        if (query.context().get(RagContextKeys.TRACE) instanceof RagTrace trace) {
            trace.transformedQuery(finalText);
        }
        return query.mutate().text(finalText).build();
    }

    // Step A: remove polite prefixes and trailing question marks
    String getCleanedText(String text) {
        String withoutPrefixes = text.strip().replaceAll("(?i)^((please|can you|could you|tell me)[,\\s]*)+", "");
        return withoutPrefixes.replaceAll("\\s*\\?+\\s*$", "");
    }

    // Step B: replace abbreviations with the full terms used in the documents
    String applyDomainSynonyms(String text) {
        String updatedText = text;
        for (Map.Entry<Pattern, String> entry : replacements.entrySet()) {
            updatedText = entry.getKey().matcher(updatedText).replaceAll(entry.getValue());
        }
        return updatedText;
    }
}
