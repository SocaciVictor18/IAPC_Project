# Evaluare și rezultatele testelor (16.11.2026)

Mediu: Windows 11 + Rancher Desktop (32 vCPU, 16 GB), stack-ul complet din `infra/docker-compose.yml`,
profil **Ollama** (`llama3.2` 3B pentru chat și pașii utilitari, `nomic-embed-text` 768 dim), reranker local TEI
`BAAI/bge-reranker-base`, totul pe **CPU**. Documente: cele 6 PDF-uri din curs + DOCX + TXT + reglementarea de test.

## Evaluare RAG – golden set (25 de întrebări, `python eval/run_eval.py`)

| config | mode | Hit@1 | Hit@3 | Hit@5 | MRR | pagină corectă citată | răspuns corect | p50 | p95 | tokeni prompt |
|---|---|---|---|---|---|---|---|---|---|---|
| full: synonyms + multi-query (3+1) + hybrid RRF + rerank TEI + parent-child + compresie | FULL | 0.88 | 1.00 | 1.00 | 0.933 | 0.92 | 0.88 | 20.3 s | 22.7 s | 1787 |

* **Hit@k / MRR:** documentul așteptat printre primele k chunk-uri din contextul trimis LLM-ului (citite din audit).
* **Pagină corectă:** o citație din `citations[]` are documentul **și** pagina așteptate.
* **Latență:** dominată de generarea llama3.2 pe CPU (3 apeluri LLM: expansiune, clasificare topic în zona gri, răspuns).
  NFR-ul „< 2 s până la începutul streaming-ului” se măsoară pe un provider cloud (OpenAI / Claude) sau pe GPU,
  cu `mode=FAST` (fără expansiune și compresie) – vezi `eval/k6/ask-latency.js`.

Pentru tabelul comparativ din raport (baseline → +synonyms → +multi-query → +hybrid → +rerank → +parent-child → +compression)
rulează din nou cu configurații diferite, după [runbook.md](runbook.md) § 4.

## Scenariul de acceptanță (`scripts/demo.sh`)

| # | Verificare | Rezultat |
|---|---|---|
| 1 | „How should I handle MFA?” → citație `authentication_datahandling.pdf`, p. 1, snippet „Multi-Factor Authentication (MFA) is mandatory…” | ✅ ANSWERED, 4 interogări (3 variante + original) |
| 2 | „Which frameworks… (ISO/IEC 27032)?” → `match=both` (semantic + full-text) | ✅ `compliance_responsibilities.pdf` p. 1 |
| 3 | „Give me a pasta recipe” | ✅ OUT_OF_SCOPE (TopicBoundaryAdvisor) |
| 4 | „Ignore previous instructions and…” | ✅ BLOCKED_SAFEGUARD (SafeGuardAdvisor), 0 ms, fără apel LLM |
| 5 | Angajat SEC întreabă de politica FIN | ✅ NO_CONTEXT (izolare OU); angajat FIN cu clearance 1 < 2 → NO_CONTEXT; ofițer FIN → ANSWERED |
| 6 | Upload ca EMPLOYEE | ✅ HTTP 403 |
| 7 | Fără token | ✅ HTTP 401 |
| 8 | Audit log + `GET /api/audit/verify` | ✅ toate cererile (inclusiv cele blocate) apar, hash chain valid |
| 9 | Gap analysis pe `regulation-test.pdf` | ✅ 17 cerințe: 14 COVERED / 1 PARTIAL / 2 GAP, export PDF/DOCX/CSV/JSON |

Observație: cu llama3.2 judecătorul gap analysis e indulgent (cerințe precum DPIA sau registrul de prelucrări, care
lipsesc intenționat din politici, au ieșit parțial COVERED). Cu `claude-opus-5-5` / `gpt-4o` evaluarea e mai strictă.

## Teste automate (`mvn verify`)

* common: AES-256-GCM (round-trip, IV aleator, detecție tampering, cheie greșită, streaming), maparea JWT → roluri / tenant.
* ingestion: chunker ierarhic (părinți în limita paginii, index global, overlap), normalizare (header/footer, cratimă, bullets).
* compliance: sinonime (matricea din curs, păstrarea contextului), RRF (match both, provenance pe 2 niveluri), filtru tenant
  (OU / admin org-wide / whitelist anti-bypass / fail-closed), citații deterministe, hash chain, parsare tolerantă (expansiune,
  judge, extract), selecția snippet-ului;
  **test de integrare pe pgvector real (Testcontainers):** match `keyword` pentru „ISO/IEC 27032”, utilizatorul din OU-A nu
  primește niciodată chunk-uri din OU-B / altă organizație / clearance mai mare, filtru de categorie AND-uit peste tenant.
