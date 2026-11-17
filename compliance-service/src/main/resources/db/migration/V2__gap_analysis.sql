-- Automated gap analysis: one report per regulation, one finding per requirement.

create table compliance.gap_report (
    id                     uuid primary key,
    organization_id        uuid not null,
    org_unit_id            uuid not null,
    regulation_document_id uuid not null,
    regulation_title       text,
    requested_by_email     text,
    status                 text not null,      -- RUNNING | COMPLETED | FAILED
    progress_pct           int not null default 0,
    total_requirements     int,
    covered                int,
    partial                int,
    gaps                   int,
    summary                text,
    created_at             timestamptz not null default now(),
    finished_at            timestamptz
);

create table compliance.gap_finding (
    id               uuid primary key,
    report_id        uuid not null references compliance.gap_report (id) on delete cascade,
    ordinal          int not null,
    requirement_ref  text,
    requirement_text text not null,
    requirement_page int,
    status           text not null,            -- COVERED | PARTIAL | GAP
    severity         text,                     -- HIGH | MEDIUM | LOW
    rationale        text,
    recommendation   text,
    evidence         jsonb                     -- [{source, page, snippet}]
);
