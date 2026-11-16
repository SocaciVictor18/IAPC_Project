# Runbook

## 1. Pornire cu Docker Compose (stack complet)

```bash
cp infra/.env.example infra/.env
```

Completează în `infra/.env`:

* `OPENAI_API_KEY` (profilul implicit OpenAI) – sau varianta Ollama de mai jos;
* `DATA_ENCRYPTION_KEY` – `openssl rand -base64 32` (32 bytes, AES-256). **Nu o schimba după ce există date** (părinții și fișierele nu mai pot fi decriptate);
* `SERVICE_CLIENT_SECRET` – orice șir lung aleator.

```bash
docker compose -f infra/docker-compose.yml up -d --build
docker compose -f infra/docker-compose.yml ps
```

Primul start durează câteva minute (build Maven în container, descărcarea modelului reranker ~1 GB).
Ordinea e controlată de healthcheck-uri: postgres → eureka → auth → ingestion → compliance; gateway-urile și nginx pornesc imediat.

| URL | Ce e |
|---|---|
| http://localhost | UI demo (prin Nginx → gateway) |
| http://localhost:8761, :8762 | Eureka (cele 2 noduri ale clusterului) |
| http://localhost:8080, :8081 | cele 2 gateway-uri direct |
| http://localhost:8000 | dashboard JobRunr (ingestie asincronă) |
| http://localhost:9090, :3000 | Prometheus / Grafana (profil `observability`, admin / `GRAFANA_ADMIN_PASSWORD`) |
| localhost:5433 | PostgreSQL (`postgres` / `POSTGRES_PASSWORD`) |

Documente demo + scenariul de acceptanță:

```bash
bash scripts/seed-documents.sh
bash scripts/demo.sh
```

### Varianta 100% locală cu Ollama

În `infra/.env`: `CHAT_PROVIDER=ollama`, `EMBEDDING_PROVIDER=ollama`, `UTILITY_MODEL=llama3.2`, `EMBEDDING_DIMS=768`.

```bash
docker compose -f infra/docker-compose.yml --profile local-llm up -d --build
docker compose -f infra/docker-compose.yml exec ollama ollama pull llama3.2
docker compose -f infra/docker-compose.yml exec ollama ollama pull nomic-embed-text
```

Pe CPU, llama3.2 (3B) răspunde în câteva secunde; ținta NFR de < 2 s pentru începutul streaming-ului se măsoară realist
pe profilul OpenAI (sau cu GPU). Dimensiunea embedding-urilor (768 vs 1536) e fixată la crearea tabelei: la schimbarea
providerului pornește de la o bază nouă (`docker compose down -v`) și re-încarcă documentele.

### Varianta Claude (Anthropic) + embeddings Ollama

În `infra/.env`: `CHAT_PROVIDER=anthropic`, `ANTHROPIC_API_KEY=sk-ant-...`, `ANTHROPIC_CHAT_MODEL=claude-opus-5-5`,
`UTILITY_MODEL=claude-haiku-5-5`, `EMBEDDING_PROVIDER=ollama`, `EMBEDDING_DIMS=768`; pornește cu `--profile local-llm`
(Ollama servește doar `nomic-embed-text`). Integrarea folosește modulul `spring-ai-anthropic` (peste SDK-ul oficial
`anthropic-java`). Modelele Claude curente resping parametrii de sampling, așa că pe această rută nu se trimite `temperature`
(vezi `AiConfig.utilityChatClientBuilder`). Dacă treci de la OpenAI (1536 dim) la Ollama/Claude (768 dim) ai nevoie de o bază nouă.

### Observabilitate

```bash
docker compose -f infra/docker-compose.yml --profile observability up -d
```

Grafana → folderul *EduPulse* → „EduPulse AI – RAG & ingestion” (latență /ask, TTFT p95, pașii RAG, outcome-uri, ingestie).

## 2. Rulare din IDE (dezvoltare)

```bash
docker compose -f infra/docker-compose.dev.yml up -d
```

Pornește doar Postgres (5433) și reranker-ul (8089). Apoi rulează, în ordine, `DiscoveryServerApplication`,
`AuthServiceApplication`, `IngestionServiceApplication`, `ComplianceServiceApplication`, `ApiGatewayApplication`
cu variabilele de mediu `DATA_ENCRYPTION_KEY`, `SERVICE_CLIENT_SECRET`, `OPENAI_API_KEY` (sau cele Ollama). Valorile implicite
din `application.yml` țintesc `localhost` (Eureka 8761, auth 9001, ingestion 9002, compliance 9003, gateway 8080).

## 3. Demo de reziliență (NFR 99.9% / Eureka cluster)

```bash
docker compose -f infra/docker-compose.yml up -d --scale compliance-service=2 --scale ingestion-service=3
docker compose -f infra/docker-compose.yml stop eureka-1
docker compose -f infra/docker-compose.yml stop api-gateway-1
```

În Eureka (:8762) apar instanțele multiple. Cererile prin http://localhost continuă să meargă: Nginx trece pe gateway-ul 2,
clienții Eureka folosesc nodul 2, iar gateway-ul face retry pe GET-uri pe altă instanță. Cu mai multe replici de ingestion,
joburile JobRunr din coadă sunt preluate de toate instanțele (scale-out dinamic). 99.9% este un SLO de producție (multi-AZ);
aici se demonstrează mecanismul.

## 4. Evaluare RAG și load testing

```bash
python eval/run_eval.py --base http://localhost --label full
```

Rezultatele (Hit@1/3/5, MRR, acuratețea paginii citate, acuratețea răspunsului, latență p50/p95, tokeni) se adaugă în
`eval/results/summary.md`. Pentru tabelul comparativ din raport repornește `compliance-service` cu altă configurație
și rulează din nou cu alt `--label`, de exemplu:

| label | variabile de mediu pentru compliance-service |
|---|---|
| `no-rerank` | `RERANK_PROVIDER=none` |
| `no-compression` | `COMPRESSION_MODE=none` |
| `no-expansion` | `RAG_QUERYEXPANDER_ENABLED=false` |
| `fast` | (fără restart) `--mode FAST` |

```bash
k6 run eval/k6/ask-latency.js -e BASE=http://localhost
k6 run eval/k6/ingest-concurrency.js -e BASE=http://localhost
```

## 5. Operare

* **Re-indexare document:** `POST /api/documents/{id}/reindex` (șterge vectorii + părinții și re-procesează).
* **Integritatea audit-ului:** `GET /api/audit/verify` (ADMIN / AUDITOR).
* **Reset complet:** `docker compose -f infra/docker-compose.yml down -v` (șterge baza, fișierele încărcate, modelele).
* **Log-uri JSON structurate:** `LOG_FORMAT=ecs` în `infra/.env`.

## 6. Probleme frecvente

| Simptom | Cauză / rezolvare |
|---|---|
| `expected 1536 dimensions, not 768` | baza a fost creată cu alt `EMBEDDING_DIMS`; `down -v` și re-seed. |
| Răspunsuri „I don't know” peste tot | documentele nu sunt `INDEXED` (vezi tab-ul Documents) sau utilizatorul e în altă unitate / are clearance mai mic. |
| Rerank lipsă în citații | containerul `tei-reranker` încă descarcă modelul; până atunci se păstrează ordinea RRF (fallback). |
| 401 după 15 minute | token-ul de acces a expirat; UI-ul cere re-autentificare (sau `POST /api/auth/refresh`). |
