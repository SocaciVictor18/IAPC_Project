# EduPulse AI – Intelligent Multi-Tenant Compliance & Audit Assistant

Asistent de conformitate și audit intern pentru un spital (Helix Health Hospital): documentele spitalului
(reglementări, politici, rapoarte de audit) sunt indexate, iar personalul pune întrebări și primește răspunsuri
cu citații exacte.

Stack: Java 21, Spring Boot 4.1, Spring AI 2.0, Spring Cloud (Gateway, Eureka), PostgreSQL + pgvector, Docker Compose.

## Module

* `common` – cod comun: identitatea utilizatorului din JWT (`TenantContext`), securitate, criptare AES-256-GCM

## Pornire

```bash
cp infra/.env.example infra/.env
docker compose -f infra/docker-compose.yml up -d
```
