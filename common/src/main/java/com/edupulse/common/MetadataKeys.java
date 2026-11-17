package com.edupulse.common;

/**
 * Keys of the JSON metadata stored with every child chunk in {@code ingestion.rag_vector_store}.
 * Written by the ingestion service, read by the compliance service (tenant filter, citations, parent lookup).
 */
public final class MetadataKeys {

    private MetadataKeys() {
    }

    // who may see the chunk (tenant filter)
    public static final String ORGANIZATION_ID = "organization_id";
    public static final String ORG_UNIT_ID = "org_unit_id";
    public static final String CLEARANCE_LEVEL = "clearance_level";

    // which document it comes from
    public static final String DOCUMENT_ID = "document_id";
    public static final String DOCUMENT_VERSION = "document_version";
    public static final String SOURCE = "source";
    public static final String TITLE = "title";
    public static final String CATEGORY = "category";
    public static final String DOC_TYPE = "doc_type";
    public static final String UPLOADED_AT = "uploaded_at";

    // where it is (for citations)
    public static final String PAGE_NUMBER = "page_number";
    public static final String SECTION_REF = "section_ref";
    public static final String SNIPPET = "snippet";

    // parent-child chunking
    public static final String PARENT_ID = "parent_id";
    public static final String PARENT_INDEX = "parent_index";
    public static final String CHUNK_INDEX = "chunk_index";

    // added during retrieval (not stored)
    public static final String HYBRID_SCORE = "hybrid_score";
    public static final String RERANK_SCORE = "rerank_score";
    public static final String MATCH = "match";
    public static final String CHILD_SNIPPET = "child_snippet";
    public static final String CITATION_LABEL = "citation_label";
}
