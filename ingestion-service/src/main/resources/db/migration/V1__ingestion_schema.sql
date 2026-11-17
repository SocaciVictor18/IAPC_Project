-- Ingestion service schema (owned by ingestion_app).
-- The vector table is created here and NOT by PgVectorStore.initializeSchema, because it needs
-- the extra generated tsvector column used by hybrid (semantic + full-text) search.

create table ingestion.document (
    id                uuid primary key,
    organization_id   uuid not null,
    org_unit_id       uuid not null,
    title             text,
    original_filename text not null,
    mime_type         text not null,
    size_bytes        bigint not null,
    sha256            char(64) not null,
    category          text not null check (category in ('INTERNAL_POLICY', 'REGULATION', 'AUDIT_REPORT')),
    doc_type          text,
    clearance_level   smallint not null default 1 check (clearance_level between 1 and 5),
    version           int not null default 1,
    status            text not null check (status in ('UPLOADED','QUEUED','PARSING','CHUNKING','EMBEDDING','INDEXED','FAILED','DELETED')),
    progress_pct      smallint not null default 0,
    error_message     text,
    storage_path      text not null,
    page_count        int,
    parent_count      int,
    chunk_count       int,
    uploaded_by       uuid not null,
    uploaded_at       timestamptz not null default now(),
    indexed_at        timestamptz,
    unique (org_unit_id, sha256, version)
);

create index document_tenant_idx on ingestion.document (organization_id, org_unit_id, status);

-- Parent sections (~1000 tokens). Encrypted with AES-256-GCM: never searched, only hydrated
-- by id after a child chunk matched.
create table ingestion.parent_chunk (
    id           uuid primary key,
    document_id  uuid not null references ingestion.document (id) on delete cascade,
    parent_index int not null,
    page_number  int,
    section_ref  text,
    content_enc  bytea not null,
    token_count  int,
    unique (document_id, parent_index)
);

-- Child chunks (~200 tokens) + embeddings. Column layout matches Spring AI's PgVectorStore
-- (id, content, metadata, embedding) plus the generated full-text column.
create table ingestion.rag_vector_store (
    id          uuid primary key default gen_random_uuid(),
    content     text,
    metadata    json,
    embedding   public.vector(${embedding_dims}),
    content_tsv tsvector generated always as (to_tsvector('english', coalesce(content, ''))) stored
);

create index rag_vs_hnsw on ingestion.rag_vector_store using hnsw (embedding public.vector_cosine_ops);
create index rag_vs_tsv on ingestion.rag_vector_store using gin (content_tsv);
create index rag_vs_org on ingestion.rag_vector_store ((metadata::jsonb ->> 'organization_id'));
create index rag_vs_ou on ingestion.rag_vector_store ((metadata::jsonb ->> 'org_unit_id'));
create index rag_vs_doc on ingestion.rag_vector_store ((metadata::jsonb ->> 'document_id'));

-- The compliance service reads the vector store and parents directly (ADR-003).
do $$
begin
    if exists (select 1 from pg_roles where rolname = 'compliance_app') then
        grant usage on schema ingestion to compliance_app;
        grant select on ingestion.rag_vector_store, ingestion.parent_chunk, ingestion.document to compliance_app;
    end if;
end
$$;
