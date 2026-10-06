# Member C Operational Guidelines & Execution Rules

> **Scope:** Dedicated operational guidelines for **Member C** working on the Capstone Microservices Platform.
> **Sources of Truth:** `docs/TEAM-GUIDE.md` and *Capstone Project Brief (Trainee Handbook)*.
> **Guiding Principle:** Stay strictly within Member C's assigned scope. Never touch, modify, or format files owned by Member A or Member B.

---

## 1. Absolute File Ownership & Scope Boundaries

### ✅ What Member C Owns (Permitted Files)
You are permitted to create, edit, and commit code **only** within the following paths:

1. **Microservices**:
   - `services/payment-service/**` (100% owned)
   - `services/order-service/src/main/java/com/ecommerce/order/analytics/**` (Bonus B2 read side)
   - `services/order-service/src/test/java/com/ecommerce/order/analytics/**`
   - `services/order-service/src/main/resources/db/migration/V50__*` and above (**`V50+` only**)

2. **Configurations**:
   - `config-repo/payment-service.yml`
   - `config-repo/application.yml` — **ONLY** inside this designated comment block:
     ```yaml
     # --- management/metrics (C) ---
     ```
   - `config-repo/order-service.yml` — **ONLY** inside this designated comment block:
     ```yaml
     # --- analytics (C) ---
     ```

3. **Containers, CI & Infrastructure**:
   - `*/Dockerfile` (all 8: multi-stage, non-root, `HEALTHCHECK`)
   - `.github/workflows/ci-template.yml` (test gate → build → push to GHCR, JaCoCo ≥ 60 %, security guards)
   - `deployment/docker/docker-compose.yml`
   - `deployment/docker/prometheus/prometheus.yml`
   - `deployment/docker/grafana/**` (provisioning + `platform-overview` and `order-analytics` dashboards)
   - `deployment/kubernetes/**` (kind config + infra manifests)
   - `scripts/create-k8s-secrets.sh`

4. **Documentation**:
   - `docs/architecture/01-bonus-boundaries.*` (drawing: service boundaries of the Bonus)
   - `docs/adr/ADD-TEAM.md` — Sections **2** (Bounded Context — Bonus), **4** (Data Model), **8** (Test & Load Plan + Risks)
   - `README.md` — **ONLY** under your assigned headings:
     - `## Payment`
     - `## Kubernetes`
     - `## Bonus B2 — Order Analytics`

---

### 🚫 Strictly Forbidden Paths (Owned by Member A or Member B)
You must **NEVER** edit, create, refactor, reformat, or delete files in these paths:

| Path / Component | Owner | Action if a change is needed |
|:---|:---:|:---|
| `services/order-service/**` (outside `analytics/` and `V50+`) | **Member B** | Analytics only **reads** order events; never touch the write side |
| `services/inventory-service/**` | **Member B** | Payment consumes `InventoryReserved`; do not edit |
| `platform/config-server/**` · `platform/eureka-server/**` | **Member B** | Request via PR |
| `platform/api-gateway/**` · `config-repo/api-gateway.yml` | **Member A** | Ask A for the `/api/v1/analytics/**` (ADMIN) route |
| `services/product-service/**` · `services/notification-service/**` | **Member A** | Request via PR |
| `deployment/helm/**` · `deployment/argocd/**` | **Member A** | Helm consumes your images and Secrets |
| `deployment/docker/keycloak/**` | **Member A** | Request via PR |
| `k6/**` | **Member A** | Request via PR |
| `scripts/verify-l0.sh` · `scripts/e2e-check.sh` | **Member B** | Run them; do not edit |

---

## 2. Mandatory Package Architecture & Folder Design

`payment-service` and the analytics module follow the uniform layered structure:

```
src/main/java/com/ecommerce/payment/            src/main/java/com/ecommerce/order/analytics/
├── PaymentServiceApplication.java              ├── api/             # AnalyticsController, DTO records
├── api/             # Controllers, DTOs        ├── application/     # Projector, query service
├── application/     # Use cases, @Transactional├── domain/          # Read-model types
├── domain/          # Payment, events          └── infrastructure/  # Own Kafka factory + listener, repository
└── infrastructure/  # Outbox, persistence, messaging, security

src/main/resources/
├── application.yml  # Minimal: spring.application.name + config-server import ONLY
└── db/migration/    # V<n>__<description>.sql
```

### Dependency Flow Rules:
$$\text{api} \longrightarrow \text{application} \longrightarrow \text{domain} \longleftarrow \text{infrastructure}$$
* **Domain purity:** The `domain` package must NEVER import Spring Web or Kafka classes.
* **Analytics isolation:** the analytics module declares its **own** listener container factory and consumer group
  (`order-service-analytics`); it must never change order-service's default Kafka beans.

---

## 3. Flyway Migration Versioning Reservation

To prevent database migration conflicts with Member B in `order-service`:
* **Member B Range:** **`V1__` through `V49__`**.
* **Member C Range:** **`V50__` and above** (e.g., `V50__create_order_analytics.sql`).
* `spring.flyway.out-of-order: true` is set in order's B block so both ranges apply on any database.
* **Rule:** Never edit an existing, merged migration file. Always add a new version.

In `payment-service`: Member C owns all migrations starting from `V1__`.

---

## 4. Architectural Invariants & Clean Code Rules

1. **Payment exactly once (FR-08):**
   - `POST /api/v1/payments` requires `Idempotency-Key`; same key + same body → stored response replayed with `Idempotent-Replayed: true`; same key + different body → `422`.
   - `UNIQUE(order_id)` in `payments`: the database, not application code, guarantees one charge per order.
2. **Transactional Outbox (reference design for the team):**
   - Never call `kafkaTemplate.send()` inside `@Transactional`; write `outbox_event` in the same transaction.
   - Store the W3C `traceparent` on every outbox row and send it as a Kafka header (NFR-06).
3. **Idempotent consumers (NFR-10):**
   - `processed_event(event_id, consumer)` written in the same transaction as the state change; catch `DataIntegrityViolationException`.
   - `DefaultErrorHandler`: 3 retries 1 s apart, then `<topic>.DLT`; unreadable events skip retries.
4. **B2 projection (FR-16):**
   - A redelivered event never changes a count (`analytics_processed_event`); a final status is never overwritten.
   - `GET /api/v1/analytics/summary` is ADMIN only; metrics `analytics_orders_total{status}`, `analytics_revenue_total`.
   - Grafana *Order Analytics*: ≥ 3 panels (orders/min by status, revenue, Saga failure rate).
5. **Containers & CI (L4, NFR-04, NFR-07):**
   - Multi-stage Dockerfiles, final `USER` non-root, `HEALTHCHECK` on `/actuator/health`.
   - CI: gitleaks + non-root guard → `mvn verify` (JaCoCo ≥ 60 % on `*.application`) → build → push `ghcr.io/<owner>/<service>:<sha>` on `main`.
   - A module's surefire `<argLine>` must start with `@{argLine}`, or JaCoCo loses its agent and the gate silently skips.
6. **Kubernetes & Secrets:**
   - Every secret comes from `deployment/docker/.env` through `scripts/create-k8s-secrets.sh`; nothing secret in manifests or Git.
   - Compose and Kubernetes pass the **same variable names** the `${...}` placeholders in `config-repo/` expect.
7. **Constructor Injection Only:** `private final` fields; zero `@Autowired` on fields.
8. **Error Contract:** RFC 7807 `ProblemDetail` with stable codes (`IDEMPOTENCY_KEY_REUSED`, `PAYMENT_NOT_FOUND`, …).

---

## 5. Scope Reference & Out-of-Scope Constraints

* **Reference Documents:**
  1. `docs/TEAM-GUIDE.md`
  2. *Capstone Project Brief (Trainee Handbook)*
  3. `docs/adr/ADD-TEAM.md` §2, §3 (contract), §4, §8
* **Strictly Out of Scope (Do Not Add):**
  - No Elasticsearch, MongoDB, or extra databases (B2 lives in `order_db`, Decision B2-1).
  - No extra message brokers; no Axon / Event Sourcing.
  - No new infrastructure beyond the Brief's list unless the ADD names a real problem.
  - No personal branch merges directly to `main` without PR review.

---

## 6. Daily PR & Git Workflow for Member C

* **Branch Naming:** `capstone-L<n>/<story-id>-<short-description>` (e.g., `capstone-L2/L2-03-payment-idempotency`)
* **Commit Format:** `capstone-L<n>: <short-description>` (e.g., `capstone-L4: ci-push-images-to-ghcr`)
* **PR Limit:** $\le 400$ changed lines per PR. Squash and merge into `main`.
* **Your stories:** L2-03, L3-02, L4-01, L4-02 (cluster + Secrets), L6-01 (`docs/BACKLOG.md`).
* **Daily Routine:**
  ```bash
  git switch main
  git pull origin main
  git switch -c capstone-L<n>/<task-name>
  ```
  Always rebase on `origin/main` before opening or updating a PR.
* **Before a gate:** CI green on `main`, images in GHCR, `scripts/verify-l0.sh` and `scripts/e2e-check.sh` green.
