-- Auth service schema (owned by auth_app). Seed data is inserted by DataSeeder (BCrypt + AES need Java).

create table auth.organization (
    id          uuid primary key,
    name        text not null,
    industry    text,
    created_at  timestamptz not null default now()
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
    clearance_level smallint not null default 1 check (clearance_level between 1 and 5),
    enabled         boolean not null default true,
    created_at      timestamptz not null default now()
);

create table auth.user_role (
    user_id uuid not null references auth.app_user (id) on delete cascade,
    role    text not null check (role in ('ROLE_ADMIN', 'ROLE_COMPLIANCE_OFFICER', 'ROLE_AUDITOR', 'ROLE_EMPLOYEE')),
    primary key (user_id, role)
);

-- Machine clients for the client-credentials flow (service-to-service JWTs).
create table auth.service_client (
    client_id   text primary key,
    secret_hash text not null,
    scopes      text not null
);

-- RSA signing keys. Stored in the DB so that every auth-service replica signs with the same key
-- and the JWKS is identical behind the load balancer. Private key is AES-256-GCM encrypted.
create table auth.signing_key (
    kid             text primary key,
    public_key_der  bytea not null,
    private_key_enc bytea not null,
    active          boolean not null default true,
    created_at      timestamptz not null default now()
);

create table auth.refresh_token (
    token_hash char(64) primary key,              -- sha256 of the opaque token
    user_id    uuid not null references auth.app_user (id) on delete cascade,
    expires_at timestamptz not null,
    revoked    boolean not null default false,
    created_at timestamptz not null default now()
);

create index refresh_token_user_idx on auth.refresh_token (user_id);
