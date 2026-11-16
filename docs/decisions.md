# Decizii de arhitectură (ADR)

| # | Decizie | Motiv |
|---|---|---|
| ADR-001 | **Spring Boot 4.1 + Spring AI 2.0.2 + Spring Cloud 2025.1** (nu Boot 3.x) | Spring AI 2.0 e construit pe Boot 4 / Framework 7 și nu pornește pe Boot 3.x; Boot 3.5 a ieșit din suport OSS (30.06.2026). Cerința mai specifică („Spring AI 2.0+”) câștigă. API-ul RAG e aproape identic pe Spring AI 1.1 + Boot 3.5 dacă profesorul cere explicit Boot 3. |
| ADR-002 | **pgvector**, nu Qdrant | Cerința proiectului cere explicit PostgreSQL + pgvector, iar hybrid search-ul folosește `tsvector` din aceeași bază (fuziune în SQL, aceeași tranzacție, același filtru de tenant). |
| ADR-003 | O instanță Postgres, **o schemă + un utilizator per serviciu** | Suficient pentru proiect; fiecare serviciu deține doar schema lui. Compromis documentat: compliance are `SELECT` pe tabela vectorială și pe părinți (retrieval-ul citește direct, fără hop de rețea). |
| ADR-004 | Componente custom unde Spring AI nu are echivalent | Hybrid search (`DocumentRetriever`), reranker (`DocumentPostProcessor`), compresie de context (`DocumentPostProcessor` – `CompressionQueryTransformer` din Spring AI rescrie întrebările de follow-up din *istoric*, nu comprimă contextul, deci nu e folosit), `TopicBoundaryAdvisor`, chunking parent-child. |
| ADR-005 | **Citații deterministe** | Lista `citations[]` se construiește din documentele chiar trimise LLM-ului (`DOCUMENT_CONTEXT`), nu din textul generat („Naive Citation problem” din curs). Headerele `[S#] [Source, Page]` ajută LLM-ul să citeze corect. |
| ADR-006 | Gateway reactiv, servicii Spring MVC + **virtual threads** | WebFlux pentru multe conexiuni/SSE la margine; în servicii cod imperativ simplu cu `spring.threads.virtual.enabled=true`, `ReentrantLock` în loc de `synchronized` pe I/O. |
| ADR-007 | **JobRunr** pentru ingestia asincronă | Joburi persistente în Postgres, retry, dashboard; orice replică a ingestion-service preia joburi din coadă (scale-out). |
| ADR-008 | Criptare at-rest în 3 straturi | (1) volum criptat (LUKS / BitLocker / RDS-KMS) – singurul care acoperă embedding-urile și `content` indexat; (2) AES-256-GCM în aplicație pentru nume utilizatori, text părinți, cheia RSA privată; (3) fișierele originale criptate pe disc. Vezi [security.md](security.md). |
| ADR-009 | Provideri LLM selectați prin `spring.ai.model.chat / embedding` (`openai`, `ollama`, `anthropic`) | Înlocuiește trucul `@Primary` din curs. Chat și embeddings se aleg independent: Claude (`claude-opus-5-5` + `claude-haiku-5-5` pentru pașii utilitari) nu are API de embeddings, deci se combină cu Ollama `nomic-embed-text`. OpenAI (1536 dim) și Ollama (768 dim) au dimensiuni diferite: schimbarea cere bază nouă / reindexare (`EMBEDDING_DIMS`). Pe modele mici locale (llama3.2) parserul strict al `MultiQueryExpander` și structured output-ul JSON eșuează des, de aceea există un fallback tolerant (`TracingQueryExpander`, `LenientJson`). |
| ADR-010 | Filtre client doar din whitelist | Parametrul liber `filter` din curs ar permite `… || org_unit_id == 'alt'`. Clientul poate trimite doar `category, docType, uploadedAfter, documentIds`, validate și combinate cu AND peste filtrul de tenant din JWT. |
| ADR-011 | Full-text cu **OR** peste lexeme | `websearch_to_tsquery` / `plainto_tsquery` fac AND între toate cuvintele unei întrebări în limbaj natural și aproape nu găsesc nimic; OR + `ts_rank_cd` favorizează chunk-urile cu mai mulți termeni, iar codurile exacte („27032”, „SOC 2”) primesc greutate mare. |
| ADR-012 | Istoricul conversației din audit, nu dintr-un chat-memory separat | Ultimele 3 schimburi `ANSWERED` din `query_audit` sunt trimise ca mesaje; un singur loc de adevăr, auditat. |
| ADR-013 | Cheia RSA de semnare stocată în DB (criptată) | Toate replicile auth-service semnează cu aceeași cheie, deci JWKS-ul e identic în spatele load balancer-ului. |

## Diferențe față de codul din curs (migrare la Spring AI 2.0)

* pachete corecte: `org.springframework.ai.rag.Query`, `...rag.preretrieval.query.transformation.QueryTransformer`,
  `...rag.preretrieval.query.expansion.MultiQueryExpander`, `...rag.postretrieval.document.DocumentPostProcessor`,
  `...rag.advisor.RetrievalAugmentationAdvisor` (nu `chat.client.advisor.api`);
* `QueryBuilder` nu există → `query.mutate().text(...)` (păstrează istoricul și contextul – esențial pentru tenant);
* `.withTemperature()/.withNumberOfQueries()` → `.temperature()/.numberOfQueries()`; `defaultOptions(...)` primește un `ChatOptions.Builder`;
* tabela vectorială e creată de Flyway (`initializeSchema=false`) pentru coloana `content_tsv`;
