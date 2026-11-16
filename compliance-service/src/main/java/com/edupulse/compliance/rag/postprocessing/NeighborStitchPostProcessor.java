package com.edupulse.compliance.rag.postprocessing;

import com.edupulse.common.MetadataKeys;
import com.edupulse.compliance.config.RagProperties;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.postretrieval.document.DocumentPostProcessor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Course component (neighbour chunk stitching), kept for the evaluation comparison and disabled by
 * default ({@code rag.neighbor-stitch.enabled=false}) because parent-child chunking solves the same
 * boundary problem. Fetches chunks {@code index ± radius} of the same document.
 * <p>
 * Change vs. the course: neighbours are fetched by exact metadata lookup in SQL instead of a
 * {@code similaritySearch} without query text (which would embed an empty string).
 */
@Component
public class NeighborStitchPostProcessor implements DocumentPostProcessor {

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };

    public record Target(String documentId, int index) {
    }

    private final JdbcClient jdbc;
    private final int radius;
    private final JsonMapper json = JsonMapper.builder().build();

    public NeighborStitchPostProcessor(JdbcClient jdbc, RagProperties props) {
        this.jdbc = jdbc;
        this.radius = props.neighborStitch().radius();
    }

    @Override
    public List<Document> process(Query query, List<Document> documents) {
        if (documents.isEmpty() || radius <= 0) {
            return documents;
        }
        Set<Target> targets = new LinkedHashSet<>();
        for (Document d : documents) {
            Object doc = d.getMetadata().get(MetadataKeys.DOCUMENT_ID);
            Object idx = d.getMetadata().get(MetadataKeys.CHUNK_INDEX);
            if (doc == null || idx == null) {
                continue;
            }
            int index = parseIndex(idx);
            for (int delta = 1; delta <= radius; delta++) {
                if (index - delta >= 0) {
                    targets.add(new Target(String.valueOf(doc), index - delta));
                }
                targets.add(new Target(String.valueOf(doc), index + delta));
            }
        }
        List<Document> merged = new ArrayList<>(documents);
        for (Target t : targets) {
            merged.addAll(jdbc.sql("""
                            select id::text as id, content, metadata::text as metadata from ingestion.rag_vector_store
                            where metadata::jsonb ->> 'document_id' = ? and (metadata::jsonb ->> 'chunk_index')::int = ?
                            """)
                    .params(t.documentId(), t.index())
                    .query((rs, n) -> Document.builder().id(rs.getString("id")).text(rs.getString("content"))
                            .metadata(new HashMap<>(json.readValue(rs.getString("metadata"), MAP))).build())
                    .list());
        }
        Set<String> seen = new HashSet<>();
        List<Document> out = new ArrayList<>();
        for (Document d : merged) {
            if (seen.add(d.getId())) {
                out.add(d);
            }
        }
        return out;
    }

    /** chunk_index may come back from the JSON column as a Number or a String (course note). */
    static int parseIndex(Object indexObj) {
        if (indexObj instanceof Number number) {
            return number.intValue();
        }
        return Integer.parseInt(String.valueOf(indexObj).trim());
    }
}
