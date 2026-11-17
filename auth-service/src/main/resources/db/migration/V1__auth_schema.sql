-- Auth service schema (owned by auth_app). Demo data is inserted by DataSeeder (BCrypt + AES need Java).

create table auth.organization (
    id         uuid primary key,
    name       text not null,
    created_at timestamptz not null default now()
);

create table auth.org_unit (
    id              uuid primary key,
    organization_id uuid not null references auth.organization (id),
    code            text not null,
    name            text not null,
    unique (organization_id, code)
);

create table auth.app_user (
    id              uuid primary key,
    email           text not null unique,
    password_hash   text not null,                 -- BCrypt
    full_name_enc   bytea,                         -- AES-256-GCM (personal data)
    org_unit_id     uuid not null references auth.org_unit (id),
    clearance_level smallint not null default 1,
    enabled         boolean not null default true,
    created_at      timestamptz not null default now()
);

create table auth.user_role (
    user_id uuid not null references auth.app_user (id) on delete cascade,
    role    text not null,                         -- ROLE_ADMIN, ROLE_COMPLIANCE_OFFICER, ROLE_AUDITOR, ROLE_EMPLOYEE
    primary key (user_id, role)
);
