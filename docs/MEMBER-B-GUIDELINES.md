# Member B Operational Guidelines & Execution Rules

> **Scope:** Dedicated operational guidelines for **Member B** working on the Capstone Microservices Platform.
> **Sources of Truth:** `docs/TEAM-GUIDE.md` and *Capstone Project Brief (Trainee Handbook)*.
> **Guiding Principle:** Stay strictly within Member B's assigned scope. Never touch, modify, or format files owned by Member A or Member C.

---

## 1. Absolute File Ownership & Scope Boundaries

### ✅ What Member B Owns (Permitted Files)
You are permitted to create, edit, and commit code **only** within the following paths:

1. **Microservices**:
   - `services/inventory-service/**` (100% owned)
   - `services/order-service/**` (*except* `analytics/`)
   - `platform/config-server/**` (100% owned)
   - `platform/eureka-server/**` (100% owned)

2. **Configurations**:
   - `config-repo/inventory-service.yml`
   - `config-repo/eureka-server.yml`
   - `config-repo/application.yml` — **ONLY** inside these designated comment blocks:
     ```yaml
     # --- tracing/logging (B) ---
     # --- kafka (B) ---
     ```
   - `config-repo/order-service.yml` — **ONLY** inside this designated comment block:
     ```yaml
     # --- order (B) ---
     ```

3. **Scripts & Documentation**:
   - `scripts/verify-l0.sh`
   - `docs/BACKLOG.md` (daily sprint backlog updates)
   - `docs/FINAL-REPORT.md` (Gate G4 deliverable)
   - `docs/architecture/02-order-sequence.jpg` (Sequence diagram: Happy Path + Failure Path)
   - `docs/adr/ADD-TEAM.md` — Sections **3** (API Contracts & Events), **5** (Communication & Saga), **6** (Failure Modes)
   - `README.md` — **ONLY** under your assigned headings:
     - `## Run locally`
     - `## Orders & Saga`
     - `## Observability`

---

### 🚫 Strictly Forbidden Paths (Owned by Member A or Member C)
You must **NEVER** edit, create, refactor, reformat, or delete files in these paths:

| Path / Component | Owner | Action if a change is needed |
|:---|:---:|:---|
| `platform/api-gateway/**` | **Member A** | Request a route change or header mapping via PR |
| `services/product-service/**` | **Member A** | Request via PR or consume existing API |
| `services/notification-service/**` | **Member A** | Notification consumes your events; do not edit |
| `services/payment-service/**` | **Member C** | Payment consumes your events; do not edit |
| `services/order-service/.../analytics/**` | **Member C** | Member C owns the Bonus B2 projection classes |
| `*/Dockerfile` (all 8 Dockerfiles) | **Member C** | Member C writes multi-stage container builds |
| `deployment/docker/**` (Compose, Keycloak, Grafana) | **Member C / A** | Managed by C & A |
| `deployment/kubernetes/**` & `deployment/helm/**` | **Member C / A** | Managed by C & A |
| `deployment/argocd/**` | **Member A** | Managed by A |
| `.github/workflows/**` & CI configuration | **Member C** | Managed by C |
| `k6/**` | **Member A** | Managed by A |
| `scripts/create-k8s-secrets.sh` | **Member C** | Managed by C |

---

## 2. Mandatory Package Architecture & Folder Design

Every service owned by Member B (`order-service`, `inventory-service`) must strictly implement this uniform layered structure:

```
src/main/java/com/ecommerce/<service>/
├── <Service>Application.java
├── api/             # Controllers, Request/Response DTO records, ControllerAdvice
├── application/     # Use case services, orchestration, @Transactional boundaries
├── domain/          # Entities, Enums, Value objects, Domain exceptions
└── infrastructure/  # Repositories, Feign clients, Kafka listeners, Outbox poller, Config

src/main/resources/
├── application.yml  # Minimal: spring.application.name + config-server import ONLY
└── db/migration/    # Flyway migration SQL scripts: V<n>__<description>.sql

src/test/java/com/ecommerce/<service>/
# Mirrors main structure: *Test (unit/slice) and *IT (Testcontainers integration tests)
```

### Dependency Flow Rules:
$$\text{api} \longrightarrow \text{application} \longrightarrow \text{domain} \longleftarrow \text{infrastructure}$$
* **Domain purity:** The `domain` package must NEVER import Spring Web, OpenFeign, or Kafka classes.
* **DTO Isolation:** Never expose domain entities in controllers or Kafka payloads; always use Java `record` DTOs.

---

## 3. Flyway Migration Versioning Reservation

To prevent database migration conflicts with Member C in `order-service`:
* **Member B Range:** **`V1__` through `V49__`** (e.g., `V1__init_orders.sql`, `V2__order_outbox.sql`, `V3__order_processed_events.sql`).
* **Member C Range:** **`V50__` and above** (reserved for Bonus B2 analytics tables).
* **Rule:** Never edit an existing, merged migration file. Always add a new version within `V1`–`V49`.

In `inventory-service`:
* Member B owns all migrations starting from `V1__`.

---

## 4. Architectural Invariants & Clean Code Rules

1. **No Network Calls inside `@Transactional`:**
   - In `order-service`, call `inventory-service` via OpenFeign *before* opening a database transaction.
2. **Transactional Outbox for Event Publishing:**
   - Never call `kafkaTemplate.send()` directly inside `@Transactional`.
   - Save the business entity and an `outbox_event` record in **one database transaction**.
   - An asynchronous `@Scheduled` poller reads pending outbox events using `SELECT ... FOR UPDATE SKIP LOCKED` and publishes them to Kafka.
3. **Trace Continuity Across Outbox (NFR-06):**
   - Store the active W3C `traceparent` in the `outbox_event` table.
   - Inject this `traceparent` as a Kafka record header when publishing so distributed tracing is not lost across threads.
4. **Idempotent Message Processing (NFR-10):**
   - Every Kafka consumer must maintain a `processed_event(event_id PK, consumer)` table.
   - Insert the `eventId` inside the same transaction as the state change.
   - Catch `DataIntegrityViolationException` to silently ignore duplicate events.
5. **Constructor Injection Only:**
   - Use `private final` fields with Lombok `@RequiredArgsConstructor` or explicit constructors.
   - Zero `@Autowired` on fields.
6. **Error Contract Consistency:**
   - Return RFC 7807 `ProblemDetail` with stable error codes (`OUT_OF_STOCK`, `STOCK_CHECK_UNAVAILABLE`, `ORDER_NOT_FOUND`).
   - Never expose internal database exceptions or stack traces to clients.

---

## 5. Scope Reference & Out-of-Scope Constraints

* **Reference Documents:**
  1. [docs/TEAM-GUIDE.md](file:///d:/Github/Capstone%20Microservices/capstone-platform/docs/TEAM-GUIDE.md)
  2. *Capstone Project Brief (Trainee Handbook)*
* **Strictly Out of Scope (Do Not Add):**
  - No Elasticsearch, MongoDB, or extra databases.
  - No Axon framework or Event Sourcing.
  - No alternative message brokers (RabbitMQ, ActiveMQ).
  - No personal branch merges directly to `main` without PR review.

---

## 6. Daily PR & Git Workflow for Member B

* **Branch Naming:** `capstone-L<n>/<story-id>-<short-description>` (e.g., `capstone-L2/L2-01-inventory-check`)
* **Commit Format:** `capstone-L<n>: <short-description>` (e.g., `capstone-L2: implement-inventory-check`)
* **PR Limit:** $\le 400$ changed lines per PR. Squash and merge into `main`.
* **Daily Routine:**
  ```bash
  git switch main
  git pull origin main
  git switch -c capstone-L<n>/<task-name>
  ```
  Always rebase on `origin/main` before opening or updating a PR.
