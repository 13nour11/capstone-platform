# Antigravity Agent Directives: Member B Context

## Active Persona: Member B
The developer in this workspace is acting strictly as **Member B** for the Capstone Microservices project.
All assistance, code generation, refactoring, and guidance must strictly obey the boundaries established in `docs/TEAM-GUIDE.md` and `docs/MEMBER-B-GUIDELINES.md`.

---

## Strict Behavioral Rules for the AI Assistant

### 1. Absolute File Boundary Protection
- **NEVER** edit, create, refactor, or delete files belonging to **Member A** or **Member C**:
  - ❌ `platform/api-gateway/**` (Member A)
  - ❌ `services/product-service/**` (Member A)
  - ❌ `services/notification-service/**` (Member A)
  - ❌ `services/payment-service/**` (Member C)
  - ❌ `services/order-service/.../analytics/**` (Member C)
  - ❌ `*/Dockerfile` (all 8 Dockerfiles are Member C)
  - ❌ `deployment/**` (Docker, Kubernetes, Helm, ArgoCD are Members C & A)
  - ❌ `.github/**` (Member C & A)
  - ❌ `k6/**` (Member A)
  - ❌ `scripts/create-k8s-secrets.sh` (Member C)

### 2. Permitted Working Scope for Member B
- Assist **ONLY** on files and tasks owned by Member B:
  - ✅ `services/inventory-service/**`
  - ✅ `services/order-service/**` (excluding `analytics/`)
  - ✅ `platform/config-server/**`
  - ✅ `platform/eureka-server/**`
  - ✅ `config-repo/inventory-service.yml`
  - ✅ `config-repo/eureka-server.yml`
  - ✅ `config-repo/application.yml` (ONLY inside `# --- tracing/logging (B) ---` and `# --- kafka (B) ---`)
  - ✅ `config-repo/order-service.yml` (ONLY inside `# --- order (B) ---`)
  - ✅ `scripts/verify-l0.sh`
  - ✅ `docs/BACKLOG.md`
  - ✅ `docs/FINAL-REPORT.md`
  - ✅ `docs/adr/ADD-TEAM.md` (Sections 3, 5, 6)
  - ✅ `README.md` (ONLY under `## Run locally`, `## Orders & Saga`, `## Observability`)

### 3. Layered Package Architecture (Strict Enforcement)
Whenever generating or structuring code for Member B's services (`order-service`, `inventory-service`):
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

### 4. Database & Transaction Invariants
- **Flyway version range for `order-service`**: Strictly `V1__` through `V49__`. Never `V50+` (reserved for Member C).
- **Outbox Pattern**: Never call `kafkaTemplate.send()` inside `@Transactional`. Persist to `outbox_event` in the DB transaction and publish via `@Scheduled` poller.
- **Traceparent**: Always preserve `traceparent` header in the outbox table to maintain distributed tracing (NFR-06).
- **Zero Network I/O in DB Tx**: Always execute OpenFeign stock checks before opening `@Transactional`.
- **Idempotency**: Use `processed_event` table and catch `DataIntegrityViolationException`.

### 5. Out of Scope Constraints
- Stick 100% to the *Capstone Project Brief* and `docs/TEAM-GUIDE.md`.
- No extra message brokers (no RabbitMQ), no extra databases (no Elasticsearch/MongoDB), and no event sourcing frameworks (no Axon).
