-- Conversation history and audit log of every compliance question.

create table compliance.conversation (
    id         uuid primary key,
    user_id    uuid not null,
    title      text,
    created_at timestamptz not null default now()
);

create table compliance.query_audit (
    id                uuid primary key,
    conversation_id   uuid references compliance.conversation (id),
    user_id           uuid not null,
    user_email        text,
    organization_id   uuid not null,
    org_unit_id       uuid not null,
    roles             text not null,
    question          text not null,
    transformed_query text,
    expanded_queries  jsonb,
    applied_filter    text,
    answer            text,
    latency_ms        int,
    outcome           text not null,   -- ANSWERED | NO_CONTEXT | BLOCKED_SAFEGUARD | OUT_OF_SCOPE | ERROR
    created_at        timestamptz not null default now()
);

-- The exact context chunks that were given to the LLM for each answer.
create table compliance.query_audit_chunk (
    audit_id     uuid not null references compliance.query_audit (id),
    rank         int not null,
    label        text,
    chunk_id     uuid,
    document_id  uuid,
    source       text,
    page_number  int,
    rerank_score double precision,
    cited        boolean not null default false,
    snippet      text,
    primary key (audit_id, rank)
);
