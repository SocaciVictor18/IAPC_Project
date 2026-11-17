# Decizii

| Decizie | De ce |
|---|---|
| **Spring Boot 4.1** (nu 3.x) | Cerința cere și Spring AI 2.0+, care funcționează doar pe Spring Boot 4. |
| **pgvector** (nu Qdrant) | Cerința cere PostgreSQL + pgvector; căutarea full-text (tsvector) e în aceeași bază, deci hybrid search se face simplu în SQL. |
| **JDBC** (nu Hibernate) | Tabela vectorială folosește tipuri speciale Postgres (vector, tsvector, JSON) și Spring AI lucrează oricum cu JdbcTemplate; schema e creată de Flyway. |
| **Componente proprii unde Spring AI nu are** | Spring AI nu are hybrid search, reranker, compresie de context, TopicBoundaryAdvisor sau chunking parent-child, așa că acestea sunt clase proprii pe punctele de extensie oficiale (`DocumentRetriever`, `DocumentPostProcessor`, `CallAdvisor`). |
| **Compresia de context în `ContextCompressionPostProcessor`** | `CompressionQueryTransformer` din Spring AI are nume asemănător, dar rescrie întrebările de follow-up din istoric – nu comprimă contextul. |
| **Citații construite în cod** | În curs, citațiile cerute doar prin prompt ieșeau inconsistent. Acum lista de citații vine din documentele trimise LLM-ului (`DOCUMENT_CONTEXT`), iar LLM-ul doar le referă prin [S1], [S2]. |
| **Filtrul de tenant doar din JWT** | Parametrul liber `filter` din curs ar permite unui utilizator să ceară documentele altei unități; acum utilizatorul poate adăuga doar categoria / tipul documentului, combinate cu AND peste filtrul din token. |
| **Ollama implicit, Claude sau OpenAI opțional** | Merge gratuit, local, fără cheie; providerul se schimbă din `infra/.env` (`spring.ai.model.chat` / `embedding`). Claude nu are embeddings, așa că embedding-urile rămân pe Ollama. |
| **Fallback tolerant pentru modele mici** | llama3.2 nu respectă mereu formatul cerut (adaugă numerotare sau text în jurul JSON-ului); `SafeMultiQueryExpander` și `LenientJson` curăță răspunsul. |
| **Criptare AES-256-GCM în aplicație** | Fișierele încărcate, textul părinților și numele utilizatorilor sunt criptate. Embedding-urile și textul indexat nu pot fi criptate în aplicație fără a strica căutarea; pentru ele se folosește criptarea discului (BitLocker / LUKS / AWS RDS cu KMS). |
| **Cheia RSA generată la pornire** | Simplu pentru demo (o singură instanță de auth); după restart utilizatorii se loghează din nou. |
