package com.edupulse.compliance.rag.postprocessing;

import com.edupulse.common.MetadataKeys;
import com.edupulse.common.crypto.AesGcmCipher;
import com.edupulse.compliance.rag.RagContextKeys;
import com.edupulse.compliance.rag.RagTrace;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.postretrieval.document.DocumentPostProcessor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Child → parent hydration (second half of parent-child chunking). Small children were matched;
 * the LLM receives their ~1000-token parent section instead. Parents are loaded in one query,
 * decrypted (AES-256-GCM), deduplicated by {@code parent_id} and kept in relevance order. The child's
 * own text is preserved as {@code child_snippet} so the citation shows the exact matched paragraph.
 */
@Slf4j
@Component
public class ParentContextPostProcessor implements DocumentPostProcessor {

    private final JdbcClient jdbc;
    private final AesGcmCipher cipher;

    public ParentContextPostProcessor(JdbcClient jdbc, AesGcmCipher cipher) {
        this.jdbc = jdbc;
        this.cipher = cipher;
    }

    @Override
    public List<Document> process(Query query, List<Document> documents) {
        if (documents.isEmpty()) {
            return documents;
        }
        long start = System.nanoTime();
        LinkedHashMap<String, Document> firstChildPerParent = new LinkedHashMap<>();
        List<Document> orphans = new ArrayList<>();
        for (Document d : documents) {
            Object parentId = d.getMetadata().get(MetadataKeys.PARENT_ID);
            if (parentId == null) {
                orphans.add(d);
            } else {
                firstChildPerParent.putIfAbsent(String.valueOf(parentId), d);
            }
        }
        Map<String, String> parentText = loadParents(firstChildPerParent.keySet().toArray(String[]::new));

        List<Document> out = new ArrayList<>();
        firstChildPerParent.forEach((parentId, child) -> {
            Map<String, Object> md = new HashMap<>(child.getMetadata());
            md.put(MetadataKeys.CHILD_ID, child.getId());
            // full matched child text; CitationHeaderPostProcessor narrows it to the most relevant sentences
            md.put(MetadataKeys.CHILD_SNIPPET, child.getText() == null ? md.getOrDefault(MetadataKeys.SNIPPET, "") : child.getText());
            String text = parentText.getOrDefault(parentId, child.getText());
            out.add(Document.builder().id(child.getId()).text(text).metadata(md).score(child.getScore()).build());
        });
        out.addAll(orphans);
        if (query.context().get(RagContextKeys.TRACE) instanceof RagTrace trace) {
            trace.step("parent", (System.nanoTime() - start) / 1_000_000);
        }
        return out;
    }

    private Map<String, String> loadParents(String[] ids) {
        Map<String, String> out = new HashMap<>();
        if (ids.length == 0) {
            return out;
        }
        jdbc.sql("select id::text as id, content_enc from ingestion.parent_chunk where id = any(?::uuid[])")
                .param(ids)
                .query(rs -> {
                    out.put(rs.getString("id"), cipher.decryptString(rs.getBytes("content_enc")));
                });
        return out;
    }
}
