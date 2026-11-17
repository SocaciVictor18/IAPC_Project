# EduPulse AI – Intelligent Multi-Tenant Compliance & Audit Assistant

Asistent de conformitate și audit intern pentru un spital (Helix Health Hospital): documentele spitalului
(reglementări, politici, rapoarte de audit) sunt indexate, iar personalul pune întrebări și primește răspunsuri
cu citații exacte.

Stack: Java 21, Spring Boot 4.1, Spring AI 2.0, Spring Cloud (Gateway, Eureka), PostgreSQL + pgvector, Docker Compose.

## Module

* `common` – cod comun: identitatea utilizatorului din JWT (`TenantContext`), securitate, criptare AES-256-GCM
* `discovery-server` – Eureka, pornit în cluster de 2 noduri (eureka-1 ↔ eureka-2)
* `api-gateway` – Spring Cloud Gateway: punct unic de intrare, validează JWT-ul și rutează către servicii (`lb://`)
* `infra/nginx` – Nginx în fața celor 2 gateway-uri (load balancer)
* `auth-service` – login, token JWT semnat RS256, cheia publică (JWKS), utilizatorii demo ai spitalului
* `ingestion-service` – upload PDF / DOCX / TXT (doar ADMIN și COMPLIANCE_OFFICER), chunking ierarhic (părinți ~1000 tokeni,
  copii ~200 tokeni), embeddings în pgvector, fișiere > 10 MB procesate în fundal cu JobRunr
* `compliance-service` – întrebări cu Advanced RAG (`RetrievalAugmentationAdvisor`): `DomainSynonymTransformer` (din curs),
  `MultiQueryExpander` (3 variante), hybrid search pgvector + full-text, rerank top 20 → 8, context părinte, compresie,
  citații `[S1] [Source, Page]` (din curs); filtru pe unitate și clearance din JWT; audit log cu chunk-urile folosite

## Utilizatori demo (parola `EduPulse#2026`)

| E-mail | Rol | Unitate | Clearance |
|---|---|---|---|
| `admin@helix.test` | ADMIN | tot spitalul | 3 |
| `officer@helix.test` | COMPLIANCE_OFFICER | Clinical Operations | 3 |
| `auditor@helix.test` | AUDITOR | Clinical Operations | 2 |
| `doctor@helix.test` | EMPLOYEE | Clinical Operations | 1 |
| `officer.billing@helix.test` | COMPLIANCE_OFFICER | Billing & Insurance | 3 |
| `clerk.billing@helix.test` | EMPLOYEE | Billing & Insurance | 1 |

## Pornire

```bash
cp infra/.env.example infra/.env
docker compose -f infra/docker-compose.yml up -d --build
docker compose -f infra/docker-compose.yml exec ollama ollama pull nomic-embed-text
docker compose -f infra/docker-compose.yml exec ollama ollama pull llama3.2
bash scripts/seed-documents.sh
```

Documentele demo din `sample-docs/`: cele 6 PDF-uri din curs (Internal Security Handbook), politici ale spitalului
(PDF, DOCX, TXT), o reglementare nouă pentru gap analysis și o politică a unității Billing (pentru izolarea datelor).
