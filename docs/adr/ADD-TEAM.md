# Architecture Decision Document — Enterprise E-Commerce Platform

> Brief §10.1: 8 mandatory sections. Every decision uses
> **DECISION · OPTIONS CONSIDERED · REASON · TRADE-OFF · WHAT WOULD MAKE US REVISIT**.
> One owner per section (docs/TEAM-GUIDE.md §1.3). Peer-review result (S25): `<Approved / Approved with changes / Rework>`.

---

## 1. Problem statement

<!-- Owner: A -->

**Users.** *Shoppers* browse a public catalogue and place orders from a browser or mobile client. *Admins* run the
catalogue and the stock. *Operations* (the team on call) must answer "where is order X and why?".

**Pain.** The labs left a platform built one topic at a time: security rebuilt twice, stock in memory, a Saga that
could lose or double-apply events, and no way for a stranger to run, deploy or load-test it. A shopper can be
charged for an order that is then lost; an admin cannot trust stock numbers; nobody can show that it holds under load.

**What we deliver.** One product, in its final shape: 8 services behind one secured gateway, an order flow that is
never lost and never charged twice, and the evidence that it runs on Kubernetes and meets its latency targets.

**How we measure success.**

| Outcome | Measure | Evidence |
|---|---|---|
| Shoppers can always browse | `GET /api/v1/products` p95 < 200 ms, ≥ 50 req/s through the gateway | k6 load report (NFR-02/03) |
| Ordering is fast and safe | `POST /api/v1/orders` p95 < 800 ms at 20 VUs; one charge per order | k6 report; duplicate-payment test (FR-08) |
| No lost orders | Payment down → orders stay PENDING, then complete or are compensated | chaos test (NFR-01) |
| No orphaned stock | 0 RESERVED rows older than 30 s for a CANCELLED order | SQL query in the test plan (NFR-05) |
| Only the right people change data | missing/invalid token → 401, wrong role → 403, on every protected route | `GatewaySecurityTest`, curl suite (FR-04) |
| A stranger can run it | `docker compose up` + `verify-l0.sh`; `helm install` / ArgoCD Synced-Healthy | live demo (NFR-08) |

---

## 2. Bounded context (Bonus)

<!-- Owner: C -->

**User and pain.** Operations staff (role ADMIN) cannot see order volume, revenue or Saga failures without querying databases by hand.

**Success measure.**
- `GET /api/v1/analytics/summary` answers in under 200 ms for a 24-hour window.
- The Grafana dashboard *Order Analytics* shows three panels: orders per minute by status, revenue, and Saga failure rate.
- A redelivered event never changes a count.

**What B2 owns.**
- The read model `analytics_order` and its dedup table `analytics_processed_event` (order_db, migrations `V50+`).
- The consumer group `order-service-analytics` on `order-events`.
- The endpoint `GET /api/v1/analytics/summary` and the metrics `analytics_orders_total{status}` and `analytics_revenue_total`.

**What B2 does not own.**
- The `orders` table and every order state change. Order-service writes those; B2 only reads events.
- No other service calls B2, and B2 calls no service.

### Decision B2-1 — placement of the analytics module

| | |
|---|---|
| **Decision** | A package `com.ecommerce.order.analytics` inside order-service, with its own tables and consumer group. |
| **Options** | (a) Inside order-service, as a separate read side. (b) A new `analytics-service` with its own database. (c) Grafana querying `orders` directly. |
| **Reason** | (a) adds no deployable, chart, CI job or database. The data is order data, so order_db is its natural home. (c) would couple dashboards to the write model's schema. |
| **Trade-off** | The projection shares order-service's JVM and connection pool, so a burst of events competes with order requests. Because the package is separate and the consumer group is its own, extracting it later is mechanical. |
| **Revisit when** | Projection lag shows up on the order API's P95 under k6 load, or a second consumer of the analytics data appears. |

### Decision B2-2 — one row per order instead of hourly counters

| | |
|---|---|
| **Decision** | `analytics_order(order_id PK, status, total_amount, placed_at)`. Hourly figures come from a `GROUP BY date_trunc('hour', placed_at)` query. |
| **Options** | (a) A row per order. (b) Pre-aggregated `order_stats_hourly` counters. |
| **Reason** | (a) is correct when events arrive out of order, for example `OrderConfirmed` before `OrderPlaced`, and its upserts are naturally idempotent. (b) cannot undo a count once it has been applied. |
| **Trade-off** | The read cost grows with the number of orders in the window. An index on `placed_at` keeps a 24-hour window cheap at Capstone volumes. |
| **Revisit when** | The summary query exceeds 200 ms. Then add an hourly rollup, fed from the per-order table. |

### Decision B2-3 — exactly-once effect on an at-least-once topic

| | |
|---|---|
| **Decision** | Each event's `eventId` is inserted into `analytics_processed_event` (`ON CONFLICT DO NOTHING`) in the same transaction as the upsert. Metrics move only after commit. |
| **Options** | (a) A dedup table in the same transaction. (b) Rely on upsert idempotence alone. (c) Kafka transactions. |
| **Reason** | (a) also protects the metrics, because a redelivered event never reaches the counters. (b) still double-counts metrics. (c) does not cover the database write. |
| **Trade-off** | One extra row per event. The table grows with the event count; a retention job is deferred. |
| **Revisit when** | `analytics_processed_event` passes about 1 M rows. Then add time-based cleanup older than the topic's retention. |

---

## 3. API contract + events

<!-- Owner: B (events) · each service owner for its endpoints. Frozen on Day 1; changes need every consumer's approval. -->

---

## 4. Data model

<!-- Owner: C -->

Database per service on one PostgreSQL instance (Brief §5). Schemas are created **only** by Flyway; every service runs `ddl-auto=validate`.

| Service | Database | Tables (migration) | Owner |
|---|---|---|---|
| product-service | `product_db` | *(owner A)* | A |
| order-service | `order_db` | `orders`, `order_items` (`V1__init_orders`) | B |
| order-service — B2 | `order_db` | `analytics_order`, `analytics_processed_event` (`V50__create_order_analytics`) | C |
| inventory-service | `inventory_db` | `stock`, `reservation` (`V1__init_inventory`) | B |
| payment-service | `payment_db` | `payments`, `idempotency_keys`, `processed_event`, `outbox_event` (`V1__create_payment_tables`) | C |
| notification-service | — | none; failed sends go to `<topic>.DLT` | A |

### Payment tables

| Table | Key | Purpose |
|---|---|---|
| `payments` | `id` (UUID); **`UNIQUE(order_id)`** | One payment per order. The constraint, not application code, guarantees FR-08. |
| `idempotency_keys` | `idempotency_key` | Request hash plus the stored response. A retry with the same key replays it; a different body returns `422`. |
| `processed_event` | `(event_id, consumer)` | Saga events already applied; written in the same transaction as the payment. |
| `outbox_event` | `id` (UUID, published as `eventId`) | Events awaiting publication. Columns: `topic`, `event_type`, `payload` (JSONB), `traceparent`, `published_at`, `attempts`, `last_error`. A partial index serves the poller (`WHERE published_at IS NULL`). |

### Analytics tables (B2)

| Table | Key | Purpose |
|---|---|---|
| `analytics_order` | `order_id` | Status (`PENDING`, `CONFIRMED`, `CANCELLED`), `total_amount`, `placed_at`. A final status is never overwritten. |
| `analytics_processed_event` | `event_id` | Events already applied to the projection. |

### Flyway plan

- Versions are reserved by range in the shared `order-service` folder: Member B `V1`–`V49`, Member C `V50+`.
- A merged migration is never edited; every change is a new version.
- With two version ranges in one folder, `order_db` instances that already ran `V50` will see B's later `V2`… as out of order. order-service therefore needs `spring.flyway.out-of-order: true` (Member B's config block).
- Fresh databases (CI, Testcontainers, a new compose volume) apply versions in order and are unaffected.

---

## 5. Communication

<!-- Owner: B -->

---

## 6. Failure modes

<!-- Owner: B -->

---

## 7. Security & deployment

<!-- Owner: A -->

### 7.1 Roles per endpoint (enforced at the gateway, and again in each service)

| Endpoint | Access | Enforced by |
|---|---|---|
| `GET /api/v1/products`, `GET /api/v1/products/{id}` | public (rate limited) | gateway `permitAll` + `RequestRateLimiter`; product-service `permitAll` |
| `POST/PUT/DELETE /api/v1/products[/{id}]` | `ADMIN` | gateway `hasRole(ADMIN)`; product-service `@PreAuthorize` |
| `POST /api/v1/orders`, `GET /api/v1/orders[/{id}]` | `CUSTOMER` (own orders only) | gateway `hasRole(CUSTOMER)`; order-service owner check |
| `GET /api/v1/inventory/check` | service (`SERVICE` role, client credentials) | **not exposed**: gateway `denyAll`; inventory-service checks the role |
| `GET/PUT /api/v1/inventory/{productId}`, `/api/v1/payments/**`, `/api/v1/analytics/**` | `ADMIN` | gateway `hasRole(ADMIN)` + the service |
| `/actuator/health/**`, `/actuator/prometheus` | internal | not routed by the gateway; cluster network only |
| any other path | denied | gateway `anyExchange().denyAll()` |

401/403/429 bodies follow the same RFC 7807 contract as service errors (`code` = `UNAUTHORIZED` / `FORBIDDEN` / `RATE_LIMITED`).

**D7.1 — Where JWTs are validated**
- **DECISION:** Gateway *and* every service are OAuth2 Resource Servers (Keycloak JWKS, `iss` checked).
- **OPTIONS:** (a) gateway only, services trust `X-User-*`; (b) gateway + services.
- **REASON:** pods are reachable inside the cluster; a header is trivially forged by anything that gets past the gateway.
- **TRADE-OFF:** each request verifies the signature twice (cheap: JWKS is cached), and every service needs the issuer settings.
- **REVISIT:** if a service mesh with mTLS and authorization policies (Istio) is introduced, services could trust mesh identity instead.

**D7.2 — Identity headers**
- **DECISION:** the gateway removes every incoming `X-User-*` header, then sets `X-User-Id` (sub) and `X-User-Roles` from the validated token.
- **REASON:** downstream code and logs get the identity without parsing JWTs, and a client cannot inject an identity.
- **TRADE-OFF:** the headers are convenience only; authorization decisions still use the JWT (D7.1).
- **REVISIT:** never relax the stripping; add `X-Tenant-Id` the same way if B3 is built.

**D7.3 — Token issuer in every environment**
- **DECISION:** Keycloak runs with a fixed `KC_HOSTNAME=http://localhost:8180`; services check `iss` against it and fetch keys from the in-network address (`KEYCLOAK_JWK_SET_URI`).
- **OPTIONS:** (a) `issuer-uri` discovery from each service; (b) fixed hostname + separate JWKS URI.
- **REASON:** with (a), a token fetched from the host (`localhost:8180`) and validated inside Docker/Kubernetes (`keycloak:8180`) fails with an `iss` mismatch — every call becomes 401.
- **TRADE-OFF:** the public hostname is configuration that must match in Compose, kind and the Helm values.
- **REVISIT:** when an ingress hostname exists for Keycloak, set `KC_HOSTNAME` and `KEYCLOAK_ISSUER_URI` to it.

**D7.4 — Rate limiting (FR-13)**
- **DECISION:** Redis `RequestRateLimiter` on the public product route, one token bucket per user id, else per client IP (20 req/s, burst 40, configurable).
- **REASON:** shared across gateway replicas; Redis is already in the stack (no new infrastructure).
- **TRADE-OFF:** if Redis is down the limiter **fails open** (requests pass) — availability over protection. All anonymous clients behind one NAT share a bucket.
- **REVISIT:** abuse in production → fail closed for anonymous traffic, or limit at the ingress.

### 7.2 Token propagation

| Call | Credential |
|---|---|
| Client → gateway → service | the user's bearer JWT, forwarded unchanged; the service validates it again |
| order-service → inventory-service `/check` (Feign) | **client credentials** of confidential client `order-service` (realm role `SERVICE`, FR-14), not the user's token |
| Saga events (Kafka) | no token: the broker is internal; identity fields (customer id) are copied into the event payload |

### 7.3 Secrets (NFR-04)

| Secret | Local (Compose) | Kubernetes | CI |
|---|---|---|---|
| DB passwords, Keycloak admin, `order-service` client secret, test-user password | git-ignored `.env` (template `.env.example`) | Secret `<service>-secrets` from `scripts/create-k8s-secrets.sh`, mounted with `envFrom` | — |
| GHCR push | — | `imagePullSecrets` (if packages are private) | GitHub Actions `GITHUB_TOKEN` |

The realm export contains no secret: `${ORDER_SERVICE_CLIENT_SECRET}` and `${KC_TEST_USER_PASSWORD}` are resolved
from the environment at import. Services read passwords only from environment variables.

### 7.4 Images, probes, Helm, ArgoCD

| Concern | Decision |
|---|---|
| Image | multi-stage (`maven:3.9-eclipse-temurin-21` → `eclipse-temurin:21-jre-alpine`), layered jar, `USER 10001`, `HEALTHCHECK` on `/actuator/health/liveness`, `MaxRAMPercentage=75` |
| Probes | `startupProbe` (≤ 3 min), `livenessProbe` `/actuator/health/liveness`, `readinessProbe` `/actuator/health/readiness` |
| Pod hardening | `runAsNonRoot` (10001), `readOnlyRootFilesystem` + `/tmp` emptyDir, no privilege escalation, drop `ALL`, seccomp `RuntimeDefault` |
| Least privilege | one ServiceAccount per service, no RBAC bindings, `automountServiceAccountToken: false` |
| Resources | requests 100m / 256Mi, limit 512Mi per service |

**D7.5 — Helm packaging**
- **DECISION:** one reusable chart (`deployment/helm/microservice`) + one values file per service; infrastructure as raw manifests.
- **OPTIONS:** (a) one chart per service; (b) one shared chart; (c) raw manifests everywhere.
- **REASON:** the 8 services differ only in image, port, env and Secret name; one chart keeps hardening identical everywhere (Brief: Helm for ≥ 2 services — here all 8).
- **TRADE-OFF:** a service that needs something unusual (e.g. a PVC) needs a new chart option or its own chart.
- **REVISIT:** when one service's needs diverge enough that the shared template grows conditionals for it.

**D7.6 — GitOps**
- **DECISION:** ArgoCD `infra` Application + `services` ApplicationSet, automated `prune` + `selfHeal`, tracking branch `env/dev` that CI updates with `:sha` tags.
- **OPTIONS:** (a) `helm upgrade` from CI; (b) ArgoCD tracking `main`; (c) ArgoCD tracking `env/dev`.
- **REASON:** drift is detected and reverted (demo: `kubectl scale` is undone); `main` stays protected because CI never pushes to it.
- **TRADE-OFF:** two branches to understand; a bad tag bump reaches the cluster without review (mitigated by the CI test gate).
- **REVISIT:** more environments → one `env/<name>` branch or overlay per environment.

**Replaced Defaults:** none. Eureka, Config Server, Keycloak Resource Server, Helm and ArgoCD are all kept as in the Brief.

---

## 8. Test & load plan + risks

<!-- Owner: C -->

### Test levels

| Level | Tool | Member C scope (evidence) |
|---|---|---|
| Unit | JUnit 5 + Mockito | `PaymentTest`, `PaymentSimulatorTest`, `RequestHashTest`, `RefundPaymentServiceTest`, `OutboxPublisherTest`, `OrderEventsAnalyticsListenerTest` |
| Web slice | `@WebMvcTest` + `spring-security-test` | `PaymentControllerTest` (401 / 403 / 400 / 201 / 422 / 404 / 409), `AnalyticsControllerTest` |
| Integration (Testcontainers PostgreSQL) | `postgres:16-alpine` | `PaymentIdempotencyIT` (FR-08), `HandleInventoryReservedIT` (NFR-10), `DeclinedPaymentIT` (FR-09 trigger), `OrderAnalyticsProjectorIT` (B2, no double count) |
| Messaging | `@EmbeddedKafka` + PostgreSQL | `PaymentSagaKafkaIT`: `InventoryReserved` → `PaymentCompleted`, redelivery ignored, poison message → `inventory-events.DLT` |
| Contract | Pact (order ↔ inventory) | Recommended by the Brief; owned by B |
| Coverage | JaCoCo | CI fails a module whose `*.application` packages are below **60 %** line coverage (NFR-07) |

### Load plan (k6, scripts owned by A)

| Scenario | Target | Pass criterion |
|---|---|---|
| Smoke | 1 VU, 1 min, every public route | 0 % errors |
| Load | 20 VUs, 5 min | `GET /api/v1/products` P95 < 200 ms; `POST /api/v1/orders` P95 < 800 ms; errors < 1 % (NFR-02) |
| Throughput | Constant arrival rate on product reads | ≥ 50 req/s through the gateway (NFR-03) |
| Stress | Ramp to 150 VUs | Find the first saturated resource. Watch: Hikari pool, outbox backlog (`outbox_pending`), consumer lag, bulkhead |

### Top 5 project risks

| # | Risk | Likelihood / impact | Mitigation | Owner |
|---|---|---|---|---|
| 1 | Docker memory below 6 GB cannot run the full stack and the kind cluster | High / High | Per-container `mem_limit`, JVM `MaxRAMPercentage=75` with SerialGC, one replica per service, compose stopped while kind runs | C |
| 2 | The trace breaks at the outbox, failing NFR-06 | Medium / Medium | `traceparent` stored per outbox row and restored on publish (payment reference design; B copies it) | B + C |
| 3 | A duplicate event double-charges or double-counts | Medium / High | DB unique constraints + `processed_event` in the same transaction; covered by redelivery tests | C |
| 4 | The G2 window (2 days) is too short for the Saga plus Kubernetes | High / High | Dockerfiles, CI and the kind manifests ready before G1; contracts frozen on Day 1 | Tech Lead |
| 5 | The office network blocks github.com or ghcr.io from containers, so ArgoCD or image pulls fail at G2 | High / High | Verify from the office in week 1; fallback `kind load docker-image` and a demo on another network | A + C |
