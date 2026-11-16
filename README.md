# EduPulse AI – Intelligent Multi-Tenant Compliance & Audit Assistant

Platformă de conformitate și audit intern pentru industrii reglementate (Fintech, Healthcare, Higher Ed).
Firmele încarcă reglementări, politici interne și rapoarte de audit (PDF / DOCX / TXT); angajații pun
întrebări în limbaj natural și primesc răspunsuri cu **citații exacte** (document, pagină, fragment),
iar ofițerii de conformitate pot genera automat **rapoarte de gap analysis** între o reglementare nouă și politicile existente.

```
Client (UI / Postman) ─► Nginx (ALB local) ─► Spring Cloud Gateway ×2 (JWT, rutare, rate-limit)
                                                   │  service discovery: Eureka ×2 (peer cluster)
                         ┌─────────────────────────┼──────────────────────────┐
                    auth-service            ingestion-service           compliance-service
                    JWT RS256 + JWKS        upload, parsare, chunking   Advanced RAG, guardrails,
                    useri / RBAC / OU       ierarhic, embeddings,       SSE, audit trail,
                                            JobRunr (> 10 MB)           gap analysis + export
                         └──────────── PostgreSQL 17 + pgvector (scheme auth / ingestion / compliance) ┘
                                                    TEI cross-encoder reranker · OpenAI / Ollama
```

## Funcționalități (cerințele minime din `explain.pdf`)

| Cerință | Implementare |
|---|---|
| Ingestie PDF, DOCX, TXT | `PagePdfDocumentReader` (pagină reală), `TikaDocumentReader` (DOCX → paragrafe), TXT (pagini logice de 60 linii) |
| Chunking ierarhic | `HierarchicalChunker`: părinți ~1000 tokeni (într-o singură pagină, criptați AES-256-GCM) → copii ~200 tokeni cu overlap |
| Async > 10 MB | JobRunr (joburi persistente în Postgres, 50 workeri, retry) → `202 Accepted` + URL de status |
| Hybrid search | `HybridDocumentRetriever`: pgvector HNSW (cosine) ‖ PostgreSQL `tsvector` full-text, fuziune **RRF** (k=60) |
| Multi-query expansion | `MultiQueryExpander` (3 variante + originalul), model mic la temperatură 0, cache |
| Rerank + compresie | `CrossEncoderRerankPostProcessor` (top 20 → 8, `BAAI/bge-reranker-base` local prin TEI) + `ContextCompressionPostProcessor` (extractiv, verbatim) |
| Citații stricte | `[S1] [Source: X, Page: N]` în context + lista `citations[]` construită **în cod** din documentele folosite (nu parsată din text) |
| Guardrails | `SafeGuardAdvisor` (prompt injection) + `TopicBoundaryAdvisor` custom (embedding + LLM în zona gri) + „I don't know” când nu există context |
| Multi-tenant + RBAC | filtru obligatoriu `organization_id / org_unit_id / clearance_level` din JWT pe ambele ramuri ale căutării; upload doar `ROLE_ADMIN` / `ROLE_COMPLIANCE_OFFICER` |
| Gap analysis | workflow agentic extract → retrieve → judge → aggregate, export **PDF / DOCX / CSV / JSON** |
| Audit log | fiecare întrebare (inclusiv cele blocate) + chunk-urile exacte folosite; append-only (trigger DB) + **hash chain** verificabil |

## Stack

Java 21 (virtual threads) · Spring Boot 4.1 · **Spring AI 2.0.2** · Spring Cloud 2025.1 (Gateway WebFlux, Eureka, LoadBalancer) ·
PostgreSQL 17 + pgvector · Flyway · JobRunr 9 · OpenAI (`gpt-4o`, `gpt-4o-mini`, `text-embedding-3-small`) sau Ollama (`llama3.2`, `nomic-embed-text`) ·
HuggingFace TEI (reranker) · Nginx · Docker Compose · Prometheus + Grafana · GitHub Actions (build, Gitleaks, OWASP Dependency-Check, Trivy).

> **Notă despre versiuni:** cerința spune „Spring Boot 3.x” și „Spring AI 2.0+”. Spring AI 2.0 funcționează doar pe Spring Boot 4,
> iar Boot 3.5 a ieșit din suportul OSS în iunie 2026, așa că proiectul folosește Boot 4.1 (vezi [docs/decisions.md](docs/decisions.md), ADR-001).

## Pornire rapidă

Cerințe: Docker (Desktop / Rancher Desktop) cu ~10 GB RAM alocați.

```bash
cp infra/.env.example infra/.env
```

Editează `infra/.env`: `DATA_ENCRYPTION_KEY` (generează cu `openssl rand -base64 32`), `SERVICE_CLIENT_SECRET` și alege providerul LLM:

| Provider | Setări în `infra/.env` | Profil compose |
|---|---|---|
| **Ollama – 100% local, fără cheie** (testat end-to-end) | `CHAT_PROVIDER=ollama`, `EMBEDDING_PROVIDER=ollama`, `UTILITY_MODEL=llama3.2`, `EMBEDDING_DIMS=768` | `--profile local-llm` |
| **Claude (Anthropic)** pentru răspunsuri + Ollama pentru embeddings (Anthropic nu are API de embeddings) | `CHAT_PROVIDER=anthropic`, `ANTHROPIC_API_KEY=...`, `ANTHROPIC_CHAT_MODEL=claude-opus-5-5`, `UTILITY_MODEL=claude-haiku-5-5`, `EMBEDDING_PROVIDER=ollama`, `EMBEDDING_DIMS=768` | `--profile local-llm` |
| OpenAI (implicit) | `OPENAI_API_KEY=...` | – |

```bash
docker compose -f infra/docker-compose.yml --profile local-llm up -d --build
docker compose -f infra/docker-compose.yml exec ollama ollama pull llama3.2
docker compose -f infra/docker-compose.yml exec ollama ollama pull nomic-embed-text
```

> Dacă portul 80 e ocupat (de ex. Traefik din Kubernetes-ul Rancher Desktop), pune `HTTP_PORT=8088` în `infra/.env`
> și folosește http://localhost:8088 (și `bash scripts/seed-documents.sh http://localhost:8088`).

```bash
bash scripts/seed-documents.sh
```

Deschide **http://localhost** (UI demo) și autentifică-te, de exemplu cu `officer@acme.test` / `EduPulse#2026`.
Scenariul complet de demo (citații, hybrid search, guardrails, izolare tenant, RBAC, audit, gap analysis):

```bash
bash scripts/demo.sh
```

Detalii (Ollama local, rulare din IDE, observabilitate, failover Eureka, evaluare): [docs/runbook.md](docs/runbook.md).

## Utilizatori demo (parolă `EduPulse#2026`)

| E-mail | Rol | Organizație / unitate | Clearance |
|---|---|---|---|
| `admin@acme.test` | ADMIN | Acme Fintech (vede toate unitățile) | 3 |
| `officer@acme.test` | COMPLIANCE_OFFICER | Acme / SEC | 3 |
| `auditor@acme.test` | AUDITOR | Acme / SEC | 2 |
| `employee@acme.test` | EMPLOYEE | Acme / SEC | 1 |
| `officer.fin@acme.test` | COMPLIANCE_OFFICER | Acme / FIN | 3 |
| `employee.fin@acme.test` | EMPLOYEE | Acme / FIN | 1 |
| `admin@helix.test`, `officer@helix.test` | ADMIN / CO | Helix Health / CMP | 3 |

## Structura repo-ului

```
common/              DTO-uri comune, TenantContext, mapare JWT → roluri, AES-256-GCM, Problem Details
discovery-server/    Eureka (2 peer-uri în compose)
api-gateway/         Spring Cloud Gateway: validare JWT (JWKS), rute lb://, rate limit, CORS, retry
auth-service/        login, refresh, client-credentials, JWKS, admin useri, seed
ingestion-service/   upload, validare Tika, stocare criptată, readers, chunking ierarhic, embeddings, JobRunr
compliance-service/  pipeline Advanced RAG, guardrails, SSE, audit, conversații, gap analysis, export
infra/               docker-compose, init Postgres, nginx (+ UI), Prometheus, Grafana, script volum criptat
eval/                golden set (25 întrebări), harness de evaluare (Hit@k, MRR), scripturi k6
postman/             colecție completă (39 de request-uri)
sample-docs/         cele 6 PDF-uri din curs + DOCX + TXT + o reglementare de test
docs/                arhitectură, decizii (ADR), securitate, runbook
```

## Teste

```bash
mvn verify
```

Teste unitare pentru: criptare AES-GCM, maparea JWT, chunker-ul ierarhic, normalizarea textului, transformatorul de sinonime
(matricea din curs), RRF, filtrul de tenant (inclusiv încercări de bypass), citații, hash chain-ul de audit.
Evaluarea RAG end-to-end: `python eval/run_eval.py --label full` (vezi runbook).
Rezultatele rulării locale (Hit@1 0.88, Hit@3 1.0, MRR 0.93, pagina corectă citată în 92% din cazuri) și scenariul de
acceptanță: [docs/evaluation.md](docs/evaluation.md).
