# EduPulse AI – Intelligent Multi-Tenant Compliance & Audit Assistant

Asistent de conformitate și audit intern pentru un spital (**Helix Health Hospital**). Spitalul încarcă reglementări,
politici interne și rapoarte de audit (PDF / DOCX / TXT). Personalul pune întrebări în limbaj natural și primește
răspunsuri cu **citații exacte** (document, pagină, fragment). Ofițerul de conformitate poate genera automat un
**raport de gap analysis** între o reglementare nouă și politicile existente.

## Arhitectură

```
Browser / Postman
      │
   Nginx (:80)  ──►  API Gateway ×2  (Spring Cloud Gateway: validează JWT-ul, rutează)
                          │   găsește serviciile prin Eureka ×2 (cluster)
       ┌──────────────────┼────────────────────┐
  auth-service     ingestion-service      compliance-service
  login, JWT       upload, chunking,      Q&A (Advanced RAG), guardrails,
  RS256, useri     embeddings, JobRunr    audit log, gap analysis
       └──────── PostgreSQL + pgvector ────────┘
                 Ollama (llama3.2 + nomic-embed-text) · reranker local (TEI)
```

## Cerințe → implementare

| Cerință (First Project / explain) | Unde în cod |
|---|---|
| Ingestie PDF, DOCX, TXT | `ingestion-service` → `DocumentParser` |
| Chunking ierarhic (copii ~200 tokeni, părinți ~1000) | `HierarchicalChunker`, `ParentContextPostProcessor` |
| Fișiere > 10 MB procesate asincron | `DocumentService` + `IngestionJobRunner` (JobRunr) |
| Multi-query expansion (3 variante) | `MultiQueryExpander` (template-ul din curs), în `AIProviderConfig` |
| Hybrid search pgvector + tsvector | `HybridDocumentRetriever` + `ReciprocalRankFusion` |
| Reranking top 20 → 8 (cross-encoder local) | `CrossEncoderRerankPostProcessor` |
| Compresie de context | `ContextCompressionPostProcessor` |
| `RetrievalAugmentationAdvisor` | `AIProviderConfig.ragAdvisor` |
| Guardrails: `SafeGuardAdvisor`, `TopicBoundaryAdvisor` | `AIProviderConfig`, `TopicBoundaryAdvisor` |
| Citații stricte: document, pagină, snippet | `CitationHeaderPostProcessor` (din curs) + `CitationExtractor` |
| Izolare multi-tenant (unitate + clearance) | `TenantFilterProvider` – filtru din JWT pe ambele căutări |
| RBAC: upload doar ADMIN / COMPLIANCE_OFFICER | `@PreAuthorize` în `DocumentController` |
| Gap analysis exportabil | `GapAnalysisService` (extract → retrieve → judge), export PDF / JSON |
| Audit log + chunk-urile folosite | `AuditService` (tabelele `query_audit`, `query_audit_chunk`) |
| JWT stateless validat în gateway | `auth-service` semnează RS256, gateway + servicii verifică (JWKS) |
| Criptare AES-256 | `AesGcmCipher`: fișierele încărcate, textul părinților, numele utilizatorilor |
| Eureka cluster, Nginx, Gateway | `discovery-server` ×2, `api-gateway` ×2, `infra/nginx` |
| Virtual threads, Java 21, Spring AI 2.0 | `spring.threads.virtual.enabled=true`, `pom.xml` |

Componentele din curs (*RAG with Vector Store*, *Advanced RAG Retrieval Pipeline*) sunt păstrate cu aceleași nume:
`AIProviderConfig`, `RagConfigData`, `DomainSynonymTransformer`, `CitationHeaderPostProcessor`, template-ul
`query-expander-prompt.st`, vector store-ul `ragVectorStore` și `ContextualQueryAugmenter.allowEmptyContext(false)`.

## Pornire

Ai nevoie de Docker cu ~10 GB RAM. Merge fără nicio cheie API (Ollama local).

```bash
cp infra/.env.example infra/.env
```

În `infra/.env` pune o cheie AES la `DATA_ENCRYPTION_KEY` (generată cu `openssl rand -base64 32`).

```bash
docker compose -f infra/docker-compose.yml up -d --build
```

```bash
docker compose -f infra/docker-compose.yml exec ollama ollama pull llama3.2
docker compose -f infra/docker-compose.yml exec ollama ollama pull nomic-embed-text
```

```bash
bash scripts/seed-documents.sh
```

Deschide **http://localhost** (dacă portul 80 e ocupat, pune `HTTP_PORT=8088` în `infra/.env`). Pentru toate
funcționalitățile, pe rând, din linia de comandă:

```bash
bash scripts/demo.sh
```

**Cu Claude în loc de llama3.2:** în `infra/.env` pune `CHAT_PROVIDER=anthropic`, `ANTHROPIC_API_KEY=sk-ant-...`,
`UTILITY_MODEL=claude-haiku-5-5` (cheia se creează pe console.anthropic.com). Embedding-urile rămân pe Ollama.
**Cu OpenAI:** vezi comentariile din `infra/.env.example`.

## Utilizatori demo (parola `EduPulse#2026`)

| E-mail | Rol | Unitate | Clearance |
|---|---|---|---|
| `admin@helix.test` | ADMIN | tot spitalul | 3 |
| `officer@helix.test` | COMPLIANCE_OFFICER | Clinical Operations | 3 |
| `auditor@helix.test` | AUDITOR | Clinical Operations | 2 |
| `doctor@helix.test` | EMPLOYEE | Clinical Operations | 1 |
| `officer.billing@helix.test` | COMPLIANCE_OFFICER | Billing & Insurance | 3 |
| `clerk.billing@helix.test` | EMPLOYEE | Billing & Insurance | 1 |

## Documente demo (`sample-docs/`)

* cele 6 PDF-uri din curs (*Internal Security Handbook*) – politica de securitate IT a spitalului;
* `patient-data-protection-policy.pdf`, `clinical-incident-reporting.docx`, `medical-device-security.txt` – politici ale spitalului;
* `regulation-health-data-security.pdf` – reglementarea nouă pentru gap analysis (inspirată din HIPAA și GDPR);
* `billing-insurance-claims-policy.txt` – doar pentru unitatea Billing (demonstrează izolarea datelor).

## Structura

```
common/              cod comun: TenantContext, securitate JWT, criptare AES
discovery-server/    Eureka
api-gateway/         Spring Cloud Gateway
auth-service/        login, JWT, utilizatori demo
ingestion-service/   upload, parsare, chunking, embeddings
compliance-service/  RAG, guardrails, audit, gap analysis
infra/               docker-compose, nginx + interfața web, init Postgres
scripts/             seed-documents.sh, demo.sh
postman/             colecția Postman
docs/                arhitectură și decizii
```

Teste: `mvn verify` (unit + un test de integrare pe pgvector real cu Testcontainers).
