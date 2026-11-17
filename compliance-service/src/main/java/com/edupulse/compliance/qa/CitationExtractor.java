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

/**
 * Builds the citation list from the documents that were really sent to the LLM (not by parsing what
 * the LLM wrote). Only the passages whose label [S#] appears in the answer are returned; if the answer
 * cites nothing, all context passages are returned so the user can still check the sources.
 */
public final class CitationExtractor {

    private static final Pattern LABEL = Pattern.compile("\\[(S\\d+)]");

    private CitationExtractor() {
    }

    public static Set<String> labelsIn(String answer) {
        Set<String> labels = new LinkedHashSet<>();
        Matcher m = LABEL.matcher(answer == null ? "" : answer);
        while (m.find()) {
            labels.add(m.group(1));
        }
        return labels;
    }

    public static List<Citation> citations(List<Document> context, String answer) {
        Set<String> used = labelsIn(answer);
        List<Citation> all = new ArrayList<>();
        List<Citation> cited = new ArrayList<>();
        for (Document d : context) {
            Map<String, Object> md = d.getMetadata();
            Citation c = new Citation(str(md.get(MetadataKeys.CITATION_LABEL)), str(md.get(MetadataKeys.SOURCE)),
                    integer(md.get(MetadataKeys.PAGE_NUMBER)), str(md.get(MetadataKeys.SECTION_REF)), snippet(md),
                    number(md.get(MetadataKeys.RERANK_SCORE)), str(md.get(MetadataKeys.MATCH)));
            all.add(c);
            if (used.contains(c.label())) {
                cited.add(c);
            }
        }
        return cited.isEmpty() ? all : cited;
    }

    /** The exact context chunks used for the answer, for the audit log. */
    public static List<AuditEntry.Chunk> auditChunks(List<Document> context, String answer) {
        Set<String> used = labelsIn(answer);
        List<AuditEntry.Chunk> out = new ArrayList<>();
        int rank = 1;
        for (Document d : context) {
            Map<String, Object> md = d.getMetadata();
            String label = str(md.get(MetadataKeys.CITATION_LABEL));
            out.add(new AuditEntry.Chunk(rank++, label, uuid(d.getId()), uuid(md.get(MetadataKeys.DOCUMENT_ID)),
                    str(md.get(MetadataKeys.SOURCE)), integer(md.get(MetadataKeys.PAGE_NUMBER)),
                    number(md.get(MetadataKeys.RERANK_SCORE)), used.contains(label), snippet(md)));
        }
        return out;
    }

    private static String snippet(Map<String, Object> md) {
        Object s = md.get(MetadataKeys.CHILD_SNIPPET);
        return str(s != null ? s : md.get(MetadataKeys.SNIPPET));
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static Integer integer(Object o) {
        return o instanceof Number n ? n.intValue() : null;
    }

    private static Double number(Object o) {
        return o instanceof Number n ? n.doubleValue() : null;
    }

    private static UUID uuid(Object o) {
        try {
            return o == null ? null : UUID.fromString(String.valueOf(o));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
