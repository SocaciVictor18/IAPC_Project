package com.edupulse.compliance.qa;

import com.edupulse.common.MetadataKeys;
import com.edupulse.compliance.audit.AuditEntry;
import com.edupulse.compliance.qa.AskResponse.Citation;
import org.springframework.ai.document.Document;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Deterministic citations: context documents ↔ [S#] labels found in the answer. */
public final class CitationExtractor {

    private static final Pattern LABEL = Pattern.compile("\\[(S\\d+)]");

    private CitationExtractor() {
    }

    public static Set<String> labelsIn(String answer) {
        Set<String> labels = new LinkedHashSet<>();
        if (answer != null) {
            Matcher m = LABEL.matcher(answer);
            while (m.find()) {
                labels.add(m.group(1));
            }
        }
        return labels;
    }

    /**
     * If the answer cites labels, only those are returned (cited=true). If it cites none, every
     * context source is returned with cited=false, so the user can still verify what was used.
     */
    public static List<Citation> citations(List<Document> context, String answer) {
        Set<String> used = labelsIn(answer);
        List<Citation> all = new ArrayList<>();
        for (Document d : context) {
            Map<String, Object> md = d.getMetadata();
            String label = str(md.get(MetadataKeys.CITATION_LABEL));
            all.add(new Citation(label, used.contains(label), str(md.get(MetadataKeys.DOCUMENT_ID)),
                    str(md.get(MetadataKeys.SOURCE)), str(md.get(MetadataKeys.TITLE)), integer(md.get(MetadataKeys.PAGE_NUMBER)),
                    str(md.get(MetadataKeys.SECTION_REF)), snippet(md), dbl(md.get(MetadataKeys.VECTOR_SCORE), md.get(MetadataKeys.HYBRID_SCORE)),
                    dbl(md.get(MetadataKeys.RERANK_SCORE), null), str(md.get(MetadataKeys.MATCH))));
        }
        List<Citation> cited = all.stream().filter(Citation::cited).toList();
        return cited.isEmpty() ? all : cited;
    }

    public static List<AuditEntry.Chunk> auditChunks(List<Document> context, String answer) {
        Set<String> used = labelsIn(answer);
        List<AuditEntry.Chunk> out = new ArrayList<>();
        int rank = 1;
        for (Document d : context) {
            Map<String, Object> md = d.getMetadata();
            String label = str(md.get(MetadataKeys.CITATION_LABEL));
            out.add(new AuditEntry.Chunk(rank++, label, uuid(md.getOrDefault(MetadataKeys.CHILD_ID, d.getId())),
                    uuid(md.get(MetadataKeys.PARENT_ID)), uuid(md.get(MetadataKeys.DOCUMENT_ID)), str(md.get(MetadataKeys.SOURCE)),
                    integer(md.get(MetadataKeys.PAGE_NUMBER)), str(md.get(MetadataKeys.SECTION_REF)),
                    dbl(md.get(MetadataKeys.HYBRID_SCORE), md.get(MetadataKeys.VECTOR_SCORE)), dbl(md.get(MetadataKeys.RERANK_SCORE), null),
                    str(md.get(MetadataKeys.MATCH)), used.contains(label), snippet(md)));
        }
        return out;
    }

    private static String snippet(Map<String, Object> md) {
        Object s = md.get(MetadataKeys.CHILD_SNIPPET);
        return str(s != null && !String.valueOf(s).isBlank() ? s : md.get(MetadataKeys.SNIPPET));
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static Integer integer(Object o) {
        if (o instanceof Number n) {
            return n.intValue();
        }
        try {
            return o == null ? null : Integer.parseInt(String.valueOf(o));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Double dbl(Object o, Object fallback) {
        if (o instanceof Number n) {
            return n.doubleValue();
        }
        return fallback instanceof Number f ? f.doubleValue() : null;
    }

    private static UUID uuid(Object o) {
        try {
            return o == null ? null : UUID.fromString(String.valueOf(o));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
