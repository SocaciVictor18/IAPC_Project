package com.edupulse.common;

/**
 * Keys of the JSON metadata payload stored on every child chunk in {@code ingestion.rag_vector_store}.
 * <p>
 * Shared by the ingestion service (writer) and the compliance service (reader: tenant filters,
 * citations, parent hydration). Keeping them in one place avoids the "page_number vs pageNumber"
 * drift seen in the course notes.
 */
public final class MetadataKeys {

    private MetadataKeys() {
    }

    // --- tenancy / access control ---
    public static final String ORGANIZATION_ID = "organization_id";
    public static final String ORG_UNIT_ID = "org_unit_id";
    public static final String CLEARANCE_LEVEL = "clearance_level";

    // --- document identity ---
    public static final String DOCUMENT_ID = "document_id";
    public static final String DOCUMENT_VERSION = "document_version";
    public static final String SOURCE = "source";
    public static final String TITLE = "title";
    public static final String CATEGORY = "category";
    public static final String DOC_TYPE = "doc_type";
    public static final String UPLOADED_AT = "uploaded_at";

    // --- position (citations) ---
    public static final String PAGE_NUMBER = "page_number";
    public static final String SECTION_REF = "section_ref";
    public static final String SNIPPET = "snippet";

    // --- hierarchy ---
    public static final String PARENT_ID = "parent_id";
    public static final String PARENT_INDEX = "parent_index";
    public static final String CHUNK_INDEX = "chunk_index";

    // --- set at retrieval time (never persisted) ---
    public static final String VECTOR_SCORE = "vector_score";
    public static final String KEYWORD_SCORE = "keyword_score";
    public static final String HYBRID_SCORE = "hybrid_score";
    public static final String RERANK_SCORE = "rerank_score";
    public static final String MATCH = "match";
    public static final String CHILD_SNIPPET = "child_snippet";
    public static final String CITATION_LABEL = "citation_label";
    public static final String CHILD_ID = "child_id";
}
