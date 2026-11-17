#!/bin/bash
# Runs once, on first start of the Postgres container (docker-entrypoint-initdb.d).
# ADR-003: one instance, one schema + one least-privilege DB user per microservice.
set -euo pipefail

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<-EOSQL
    -- Extensions need superuser; they live in public and are usable from every schema.
    CREATE EXTENSION IF NOT EXISTS vector;
    CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

    REVOKE CREATE ON SCHEMA public FROM PUBLIC;

    CREATE ROLE auth_app       LOGIN PASSWORD '${AUTH_DB_PASSWORD}';
    CREATE ROLE ingestion_app  LOGIN PASSWORD '${INGESTION_DB_PASSWORD}';
    CREATE ROLE compliance_app LOGIN PASSWORD '${COMPLIANCE_DB_PASSWORD}';

    CREATE SCHEMA auth       AUTHORIZATION auth_app;
    CREATE SCHEMA ingestion  AUTHORIZATION ingestion_app;
    CREATE SCHEMA compliance AUTHORIZATION compliance_app;

    ALTER ROLE auth_app       SET search_path = auth, public;
    ALTER ROLE ingestion_app  SET search_path = ingestion, public;
    ALTER ROLE compliance_app SET search_path = compliance, public;

    -- The compliance service reads the vector store and parent chunks directly (read-only).
    GRANT USAGE ON SCHEMA ingestion TO compliance_app;
    ALTER DEFAULT PRIVILEGES FOR ROLE ingestion_app IN SCHEMA ingestion GRANT SELECT ON TABLES TO compliance_app;
EOSQL

echo "EduPulse: roles, schemas and extensions created"
