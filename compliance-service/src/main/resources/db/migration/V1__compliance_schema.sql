-- Compliance service schema (owned by compliance_app).

create table compliance.conversation (
    id              uuid primary key,
    user_id         uuid not null,
    organization_id uuid not null,
    org_unit_id     uuid not null,
    title           text,
    created_at      timestamptz not null default now()
);

create index conversation_user_idx on compliance.conversation (user_id, created_at desc);

-- Append-only audit trail of every query (including blocked ones). Rows are hash-chained per
-- organization: row_hash = sha256(prev_hash | canonical row), so any later edit is detectable.
create table compliance.query_audit (
    id                uuid primary key,
    seq               bigserial not null unique,
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
    model             text,
    prompt_tokens     int,
    completion_tokens int,
    latency_ms        int,
    ttft_ms           int,
    outcome           text not null check (outcome in ('ANSWERED', 'NO_CONTEXT', 'BLOCKED_SAFEGUARD', 'OUT_OF_SCOPE', 'ERROR')),
    ungrounded        boolean not null default false,
    streamed          boolean not null default false,
    prev_hash         char(64),
    row_hash          char(64) not null,
    created_at        timestamptz not null
);

create index query_audit_org_idx on compliance.query_audit (organization_id, created_at desc);
create index query_audit_user_idx on compliance.query_audit (user_id, created_at desc);
create index query_audit_conv_idx on compliance.query_audit (conversation_id, created_at);

-- The exact context chunks that were given to the LLM for an answer (FR "Conversation History Tracking").
create table compliance.query_audit_chunk (
    audit_id        uuid not null references compliance.query_audit (id),
    rank            int not null,
    label           text,
    chunk_id        uuid,
    parent_id       uuid,
    document_id     uuid,
    source          text,
    page_number     int,
    section_ref     text,
    retrieval_score double precision,
    rerank_score    double precision,
    match           text,
    cited           boolean not null default false,
    snippet         text,
    primary key (audit_id, rank)
);

-- Enforce append-only at the database level.
create function compliance.forbid_audit_mutation() returns trigger language plpgsql as $$
begin
    raise exception 'audit trail is append-only (% on %)', tg_op, tg_table_name;
end
$$;

create trigger query_audit_append_only before update or delete on compliance.query_audit
    for each row execute function compliance.forbid_audit_mutation();
create trigger query_audit_chunk_append_only before update or delete on compliance.query_audit_chunk
    for each row execute function compliance.forbid_audit_mutation();

-- Automated gap analysis.
create table compliance.gap_report (
    id                     uuid primary key,
    organization_id        uuid not null,
    org_unit_id            uuid not null,
    regulation_document_id uuid not null,
    regulation_title       text,
    requested_by           uuid not null,
    requested_by_email     text,
    requester_clearance    smallint not null,
    requester_roles        text not null,
    scope                  jsonb,
    status                 text not null check (status in ('QUEUED', 'RUNNING', 'COMPLETED', 'FAILED')),
    progress_pct           smallint not null default 0,
    total_requirements     int,
    covered                int,
    partial                int,
    gaps                   int,
    not_applicable         int,
    summary                text,
    error                  text,
    created_at             timestamptz not null default now(),
    finished_at            timestamptz
);

create index gap_report_org_idx on compliance.gap_report (organization_id, created_at desc);

create table compliance.gap_finding (
    id               uuid primary key,
    report_id        uuid not null references compliance.gap_report (id) on delete cascade,
    ordinal          int not null,
    requirement_ref  text,
    requirement_text text not null,
    requirement_page int,
    obligation_type  text,
    status           text not null check (status in ('COVERED', 'PARTIAL', 'GAP', 'NOT_APPLICABLE')),
    severity         text check (severity in ('HIGH', 'MEDIUM', 'LOW')),
    rationale        text,
    recommendation   text,
    evidence         jsonb
);

create index gap_finding_report_idx on compliance.gap_finding (report_id, ordinal);
