package com.edupulse.compliance.rag.postprocessing;

import com.edupulse.common.MetadataKeys;
import com.edupulse.common.crypto.AesGcmCipher;
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
 * Parent-child chunking, second half: the search matched small child chunks (~200 tokens);
 * here each child is replaced by its parent section (~1000 tokens) so the LLM gets enough context.
 * Two children of the same parent produce the parent only once; the child's metadata (source, page) is kept.
 */
@Component
public class ParentContextPostProcessor implements DocumentPostProcessor {

    private final JdbcClient jdbc;
    private final AesGcmCipher cipher;

    public ParentContextPostProcessor(JdbcClient jdbc, AesGcmCipher cipher) {
        this.jdbc = jdbc;
        this.cipher = cipher;
    }

    @Override
    public List<Document> process(Query query, List<Document> children) {
        Map<String, Document> firstChildPerParent = new LinkedHashMap<>();
        for (Document child : children) {
            Object parentId = child.getMetadata().get(MetadataKeys.PARENT_ID);
            if (parentId != null) {
                firstChildPerParent.putIfAbsent(String.valueOf(parentId), child);
            }
        }
        Map<String, String> parentTexts = loadParents(firstChildPerParent.keySet().toArray(String[]::new));

        List<Document> out = new ArrayList<>();
        firstChildPerParent.forEach((parentId, child) -> {
            String text = parentTexts.getOrDefault(parentId, child.getText());
            out.add(Document.builder().id(child.getId()).text(text).metadata(child.getMetadata()).score(child.getScore()).build());
        });
        return out;
    }

    private Map<String, String> loadParents(String[] ids) {
        Map<String, String> texts = new HashMap<>();
        if (ids.length > 0) {
            jdbc.sql("select id::text as id, content_enc from ingestion.parent_chunk where id = any(?::uuid[])")
                    .param(ids)
                    .query(rs -> {
                        texts.put(rs.getString("id"), cipher.decryptString(rs.getBytes("content_enc")));
                    });
        }
        return texts;
    }
}
