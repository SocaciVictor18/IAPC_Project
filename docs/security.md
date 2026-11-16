# Securitate

## Autentificare și autorizare

* **JWT RS256**, emis de auth-service; claim-uri `sub, email, org, orgName, ou, clr, roles, iat, exp (15 min), jti, iss=edupulse-auth`.
  Refresh token opac de 30 min, stocat doar ca SHA-256 și rotit la fiecare folosire.
* **Validare stateless în gateway și în fiecare serviciu** (JWKS `/.well-known/jwks.json`, issuer verificat) – un request care
  ocolește gateway-ul în rețeaua internă este tot respins.
* **Service-to-service:** client-credentials (`POST /api/auth/service-token`) → JWT `scope=internal`, cache-uit până aproape de expirare;
  `/internal/**` cere `SCOPE_internal` și e blocat în gateway și în Nginx.
* **RBAC:** upload / reindex / delete și pornirea gap analysis – `ADMIN`, `COMPLIANCE_OFFICER`; rapoarte – și `AUDITOR`;
  audit log – ADMIN pe organizație, CO/AUDITOR pe unitate, EMPLOYEE doar propriile întrebări; management useri – `ADMIN`.
* **Multi-tenancy:** filtrul `organization_id ∧ org_unit_id (exceptând ADMIN) ∧ clearance_level ≤ clr` se construiește
  exclusiv din JWT și se aplică pe ambele ramuri ale hybrid search-ului; retrieval-ul eșuează închis fără tenant.

## Criptare at-rest (NFR AES-256)

| Strat | Ce acoperă | Cum |
|---|---|---|
| 1. Volum | tot Postgres-ul, inclusiv embedding-uri și `content`/`content_tsv` | `infra/scripts/create-encrypted-volume.sh` (LUKS2, `aes-xts-plain64`, cheie 512 biți = AES-256-XTS); BitLocker pe Windows, FileVault pe macOS; în AWS RDS cu stocare criptată KMS (AES-256). Dovada: `cryptsetup status edupulse-pgdata`. |
| 2. Aplicație | nume utilizatori (`full_name_enc`), text părinți (`parent_chunk.content_enc`), cheia RSA privată | AES-256-GCM, IV aleator de 96 biți per mesaj, tag de 128 biți (`common/crypto/AesGcmCipher`), cheie din `DATA_ENCRYPTION_KEY` (env / secret manager). |
| 3. Fișiere | documentele originale încărcate | AES-256-GCM în flux, pe volumul `/data/uploads`. |

**De ce vectorii nu sunt criptați în aplicație:** căutarea HNSW și full-text-ul lucrează direct pe valorile din coloană;
dacă embedding-ul sau `content` ar fi criptate în aplicație, baza n-ar mai putea calcula distanțe sau `tsvector`. Protecția lor vine
din stratul 1 (criptare la nivel de stocare), plus controlul accesului (utilizator DB dedicat, filtru de tenant).

## Securitatea upload-ului

Nume fișier sanitizat (nu ajunge niciodată pe disc), whitelist de extensii, tipul real detectat cu Tika din conținut,
limită de dimensiune (gateway `RequestSize` + multipart + validator), stocare în afara web root-ului, deduplicare SHA-256,
backpressure (429 când coada de ingestie e plină).

## OWASP Top 10 / OWASP Top 10 for LLM Apps

| Risc | Măsură |
|---|---|
| LLM01 Prompt injection | `SafeGuardAdvisor` (fraze interzise, case-insensitive pe tot promptul); system prompt care tratează conținutul documentelor ca date; context delimitat. Limitare: lista e naivă și poate fi ocolită prin sinonime – de aceea există și `TopicBoundaryAdvisor`, iar răspunsul e oricum limitat la documentele tenant-ului. |
| LLM02 Sensitive information disclosure / A01 Broken access control | filtru de tenant obligatoriu pe server (nu din request), RBAC pe endpoint-uri, clearance pe documente, filtre client doar din whitelist. |
| LLM05 Improper output handling | UI-ul escapează tot textul (fără `innerHTML` cu date neescapate); CSP strict în Nginx. |
| LLM06 Excessive agency | gap analysis folosește doar operații read-only; nu există tool-uri care modifică date. |
| LLM09 Misinformation | citații deterministe, „I don't know” fără context, marcaj `ungrounded`, disclaimer „AI-assisted” în rapoarte. |
| LLM10 Unbounded consumption | rate limit în gateway, limită de 2000 caractere / întrebare, max. 30 de cerințe per gap analysis, semafoare pe apelurile LLM/embedding, cache pe expansiuni. |
| A02 Cryptographic failures | AES-256-GCM, RS256, BCrypt (cost 10), TLS terminat în Nginx/ALB în producție. |
| A03 Injection | SQL exclusiv parametrizat (inclusiv filtrul FTS), lexeme FTS citate cu `quote_literal`. |
| A05 Security misconfiguration | headere de securitate în Nginx, Actuator expune doar health/info/prometheus, servicii fără porturi publice, containere non-root. |
| A06 Vulnerable components | CI: OWASP Dependency-Check, Trivy pe imagini; Gitleaks pentru secrete. |
| A09 Logging & monitoring failures | audit append-only cu hash chain, metrici Prometheus, log-uri structurate (`LOG_FORMAT=ecs`). |

## Secrete

Doar în `infra/.env` (în `.gitignore`); în repo există numai `infra/.env.example`. În producție: AWS Secrets Manager / Vault.
