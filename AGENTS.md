# Agent Directives: Capstone Platform

## Active Persona: Member C, finishing the whole project
The developer in this workspace is **Member C** (`docs/TEAM-GUIDE.md` §1). For the final integration
pass before G4 the agent may work on **every** file in the repository, including Member A's and
Member B's, so that the open items in `docs/FINAL-REPORT.md` §6 get closed.

Ownership still matters for review: a change to another member's files goes through a PR that the
owner reviews (`.github/CODEOWNERS`). Keep each change minimal and say in the commit which open item
it closes.

---

## Rules that hold for every change

### 1. Layered package architecture
```
src/main/java/com/ecommerce/<service>/
├── <Service>Application.java
├── api/             # Controllers, Request/Response DTO records, ControllerAdvice
├── application/     # Application use-case services, @Transactional boundaries
├── domain/          # Entities, Enums, Value objects, Domain exceptions (NO Spring Web or Kafka)
└── infrastructure/  # Repositories, Feign clients, Kafka listeners, Outbox poller, Config
src/main/resources/
├── application.yml  # Minimal: spring.application.name + config-server import ONLY
└── db/migration/    # V<n>__<description>.sql
src/test/java/com/ecommerce/<service>/
```

### 2. Database & transaction invariants
- **Flyway in `order-service`**: `V1__`–`V49__` for orders (B), `V50__`+ for analytics (C). Never edit a merged migration.
- **Outbox pattern**: never call `kafkaTemplate.send()` inside a business `@Transactional`. Persist to `outbox_event` and publish from the `@Scheduled` poller.
- **Traceparent**: keep the `traceparent` column in every outbox table (NFR-06).
- **No network I/O in a DB transaction**: Feign calls happen before the transaction opens.
- **Idempotency**: `processed_event` tables plus `DataIntegrityViolationException`, never check-then-insert.

### 3. Shared files
Edit `config-repo/application.yml`, `config-repo/order-service.yml`, `README.md` and `docs/adr/ADD-TEAM.md`
inside the matching section marker or heading (`docs/TEAM-GUIDE.md` §1.2).

### 4. Out of scope
- Stick to the *Capstone Project Brief* and `docs/TEAM-GUIDE.md`.
- No extra message brokers, no extra databases, no event-sourcing frameworks.
- Never invent measurements, approvals or signatures: numbers come from a real run, approvals and signatures from the team.
