# Arhitectură

## Drumul unei cereri

1. Browserul trimite cererea la **Nginx** (load balancer peste cele două gateway-uri).
2. **API Gateway** verifică token-ul JWT (semnătura RS256, cu cheia publică de la auth-service) și trimite cererea la
   serviciul potrivit (`lb://compliance-service`), pe care îl găsește în **Eureka**.
3. Serviciul verifică din nou token-ul și citește din el cine e utilizatorul: organizația, unitatea, clearance-ul și rolurile.

## Ingestia unui document (ingestion-service)

```
upload (doar ADMIN / COMPLIANCE_OFFICER)
 → validare: extensie + tipul real al fișierului (Tika), SHA-256 împotriva duplicatelor
 → fișierul se salvează criptat AES-256
 → ≤ 10 MB: procesat imediat │ > 10 MB: job în fundal (JobRunr)
 → citire pe pagini (PDF), pe paragrafe (DOCX), pe blocuri de linii (TXT)
 → părinți ~1000 tokeni (salvați criptat) → copii ~200 tokeni
 → embedding pentru fiecare copil → pgvector, cu metadata:
   organizație, unitate, clearance, document, pagină, snippet, parent_id
```

## O întrebare (compliance-service)

```
SafeGuardAdvisor        blochează fraze de tip „ignore previous instructions”
TopicBoundaryAdvisor    cuvinte din domeniu (parolă, pacient, incident…) → acceptat; altfel un LLM mic decide IN / OUT
RetrievalAugmentationAdvisor
  1. DomainSynonymTransformer   (curs) „how should I handle MFA?” → „how should I handle multi factor authentication”
  2. MultiQueryExpander         3 reformulări + întrebarea originală = 4 căutări
  3. HybridDocumentRetriever    pentru fiecare căutare, în paralel:
                                  pgvector (sens) + full-text Postgres (cuvinte exacte, ex. „ISO/IEC 27032”)
                                  → combinate cu Reciprocal Rank Fusion
                                  → mereu cu filtrul de tenant: organizație + unitate + clearance din JWT
  4. CrossEncoderRerank         cele mai bune 20 → reranker local → păstrează 8
  5. ParentContext              fiecare copil găsit → secțiunea părinte (mai mult context pentru LLM)
  6. ContextCompression         păstrează doar propozițiile relevante pentru întrebare
  7. CitationHeader             (curs) „[S1] [Source: fișier.pdf, Page: 2]” în fața fiecărui pasaj
  8. ContextualQueryAugmenter   fără context → „I don't know based on the available documents.”
LLM → răspuns cu [S1], [S2]… → lista de citații se construiește din documentele chiar folosite
→ se salvează în audit log (întrebarea, răspunsul, cele 4 căutări, chunk-urile folosite)
```

Răspunsul poate fi și **streamed** (`/api/compliance/ask/stream`), ca utilizatorul să vadă primele cuvinte imediat.

## Gap analysis

1. **Extract** – LLM-ul împarte fiecare secțiune a reglementării în cerințe (ex. „1.1 MFA pentru acces la EHR”).
2. **Retrieve** – pentru fiecare cerință: căutare hibridă doar în politicile interne ale aceleiași unități, rerank, top 4.
3. **Judge** – LLM-ul decide COVERED / PARTIAL / GAP și indică pasajele folosite (document + pagină + snippet).
4. **Report** – numărători + rezumat executiv; export PDF sau JSON.

## Baza de date

O singură instanță PostgreSQL cu pgvector, cu câte o schemă și un utilizator separat pentru fiecare serviciu:

| Schemă | Tabele |
|---|---|
| `auth` | organization, org_unit, app_user (nume criptat), user_role |
| `ingestion` | document, parent_chunk (criptat), rag_vector_store (embedding + metadata + tsvector) |
| `compliance` | conversation, query_audit, query_audit_chunk, gap_report, gap_finding |
