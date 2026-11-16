# Arhitectură

## Fluxul unei cereri

```
Browser / Postman
   │ HTTP :80
   ▼
Nginx ── least_conn ──► api-gateway-1 / api-gateway-2   (Spring Cloud Gateway, WebFlux)
                          │ 1. validează JWT (RS256, JWKS de la auth-service, issuer edupulse-auth)
                          │ 2. rate limit per utilizator (token bucket)
                          │ 3. rutare lb://<serviciu> (Spring Cloud LoadBalancer + Eureka)
                          │ 4. token relay (header Authorization neschimbat), /internal/** blocat
                          ▼
           auth-service · ingestion-service · compliance-service
             fiecare serviciu re-validează JWT-ul (defense in depth) și aplică @PreAuthorize
```

Eureka rulează în cluster (`eureka-1` ↔ `eureka-2`, replicare peer-to-peer); toți clienții au ambele URL-uri în
`defaultZone`, deci oprirea unui nod nu oprește rutarea. Gateway-ul face retry pe GET-uri pe altă instanță.

## Servicii și date

| Serviciu | Port | Responsabilitate | Date |
|---|---|---|---|
| discovery-server ×2 | 8761 / 8762 | registry Eureka | – |
| api-gateway ×2 | 8080 / 8081 | intrare unică, JWT, rute, rate-limit, CORS | – |
| auth-service | 9001 | login (BCrypt), JWT RS256 15 min, refresh 30 min rotativ, client-credentials, JWKS, admin | schema `auth` (nume utilizatori criptate AES-GCM, cheia RSA privată criptată) |
| ingestion-service | 9002 | upload, validare, stocare criptată, parsare, chunking, embeddings, JobRunr | schema `ingestion` + `rag_vector_store` |
| compliance-service | 9003 | Q&A Advanced RAG, guardrails, SSE, audit, conversații, gap analysis | schema `compliance`; SELECT pe `ingestion.rag_vector_store`, `parent_chunk`, `document` |

O singură instanță Postgres, câte o schemă și un utilizator DB cu drepturi minime per serviciu (ADR-003).

## Ingestie

```
POST /api/documents (ADMIN / COMPLIANCE_OFFICER)
  → nume fișier sanitizat, extensie ∈ {pdf, docx, txt}, tip real detectat cu Tika (magic bytes), limită 200 MB
  → SHA-256 → dedup în aceeași unitate (409, sau versiune nouă cu replace=true)
  → fișier criptat AES-256-GCM pe disc (/data/uploads/{org}/{id}.bin)
  → ≤ 10 MB: procesare sincronă → 201 (status INDEXED)
    > 10 MB: job JobRunr → 202 Accepted + /api/documents/{id}/status
  → PARSING → CHUNKING → EMBEDDING → INDEXED / FAILED (progres în %)
```

* **Readers:** PDF pe pagini (pagina reală pentru citație), DOCX grupat pe paragrafe (`section_ref = "Heading (¶ 9-16)"`),
  TXT pe pagini logice de 60 de linii (`section_ref = "lines 61-120"`).
* **Normalizare (în paralel pe virtual threads):** eliminarea header/footer-elor repetate pe majoritatea paginilor
  („EAAlgorithm Internal Security Handbook”, „v1.0”), a numerelor de pagină, reunirea cuvintelor despărțite în silabe.
* **Chunking ierarhic:** părinți ~1000 tokeni (niciodată peste granița paginii) → copii ~200 tokeni; fiecare copil
  primește ultimele ~22 cuvinte ale fratelui anterior (overlap, pe care `TokenTextSplitter` nu îl oferă).
* **Metadata pe fiecare copil (payload pgvector):** `organization_id, org_unit_id, clearance_level, document_id, document_version,
  source, title, category, doc_type, uploaded_at, page_number, section_ref, parent_id, parent_index, chunk_index, snippet`.
* **Embeddings:** batch-uri de 64, semafor global (8 apeluri concurente) și retry exponențial (429).

## Pipeline Advanced RAG

```
SafeGuardAdvisor (order -200)        blochează prompt injection (listă configurabilă, case-insensitive)
TopicBoundaryAdvisor (order -100)    embedding vs. 25 de topicuri; zona gri → LLM structured output; altfel refuz
RetrievalAugmentationAdvisor (0)
  PRE   FollowUpCompressionTransformer   doar dacă există istoric: follow-up → întrebare standalone (CompressionQueryTransformer)
        DomainSynonymTransformer         din curs: curăță „please / can you / ?”, MFA → multi factor authentication …
        MultiQueryExpander               3 variante + originalul (template-ul din curs), cache pe text
  RET   HybridDocumentRetriever          pe fiecare interogare, în paralel (virtual threads):
          ├─ pgvector top-20 (cosine, HNSW) + filtru tenant (Filter.Expression)
          └─ full-text top-20 (tsvector, OR peste lexeme, ts_rank_cd) + același filtru (SQL parametrizat)
          → RRF k=60 → top-20
        RrfDocumentJoiner                al doilea nivel RRF peste variantele de interogare
  POST  CrossEncoderRerankPostProcessor  top-20 → top-8 (TEI bge-reranker-base; fallback la ordinea RRF, timeout 1.5 s)
        [NeighborStitchPostProcessor]    din curs, dezactivat implicit (parent-child rezolvă aceeași problemă)
        ParentContextPostProcessor       copil → textul părintelui (decriptat), dedup pe parent_id
        ContextCompressionPostProcessor  extractiv: doar propozițiile relevante (embedding), text verbatim
        CitationHeaderPostProcessor      „[S1] [Source: X, Page: N]” + eticheta în metadata
  GEN   ContextualQueryAugmenter(allowEmptyContext=false) → „I don't know based on the available documents.”
```

Contextul de tenant circulă prin `advisors(a -> a.param(...))` → `Query.context()`; `MultiQueryExpander` și
`CompressionQueryTransformer` păstrează contextul (`query.mutate()`), iar retriever-ul **refuză** să caute fără tenant.

Răspunsul API conține `citations[]` construite din `RetrievalAugmentationAdvisor.DOCUMENT_CONTEXT` (documentele chiar
trimise LLM-ului), filtrate pe etichetele `[S#]` care apar în text. Dacă LLM-ul nu citează nimic, răspunsul e marcat `ungrounded`.

`mode=FAST` sare peste expansiune și compresie (ținta < 2 s); streaming-ul SSE (`/api/compliance/ask/stream`) trimite
`meta → token* → citations → done`, iar Nginx are `proxy_buffering off` pe această rută.

## Gap analysis (workflow agentic)

1. **Extract** – secțiunile reglementării vin de la ingestion (`/internal/documents/{id}/parents`, JWT de serviciu
   `scope=internal` obținut cu client-credentials); un LLM le transformă în cerințe atomice (structured output).
2. **Retrieve** – pentru fiecare cerință: hybrid search doar în `INTERNAL_POLICY` ale aceluiași tenant → rerank top-4 → părinți.
3. **Judge** – LLM: `COVERED / PARTIAL / GAP / NOT_APPLICABLE`, severitate, motivare, recomandare, etichetele dovezilor;
   dovezile sunt mapate în cod pe document + pagină + snippet reale.
4. **Aggregate** – statistici + rezumat executiv; export PDF (OpenPDF), DOCX (Apache POI), CSV, JSON.

Rulează asincron pe virtual threads, cu maximum 5 apeluri LLM simultane; un raport întrerupt de restart e marcat FAILED.

## Audit

`compliance.query_audit` + `query_audit_chunk`: întrebarea, interogarea transformată, cele 4 interogări expandate, filtrul
aplicat, răspunsul, modelul, tokenii, latența, TTFT, outcome (`ANSWERED / NO_CONTEXT / BLOCKED_SAFEGUARD / OUT_OF_SCOPE / ERROR`)
și fiecare chunk din context (rank, scor RRF, scor rerank, match semantic/keyword/both, pagină, snippet, citat sau nu).
Tabelele sunt append-only (trigger care respinge UPDATE/DELETE) și înlănțuite cu SHA-256 per organizație
(`GET /api/audit/verify` recalculează lanțul).

## Observabilitate

Actuator + Micrometer → Prometheus (descoperire automată prin Eureka, deci și după `--scale`) → dashboard Grafana provizionat:
latența `/ask` p50/p95, **TTFT p95**, latența fiecărui pas RAG (`rag.expand`, `rag.retrieve.semantic`, `rag.retrieve.keyword`,
`rag.rerank`, `rag.compress`), rata outcome-urilor (guardrails), rezultatele și durata ingestiei, erori HTTP pe serviciu.
Dashboard-ul JobRunr (http://localhost:8000) arată joburile de ingestie asincronă.
