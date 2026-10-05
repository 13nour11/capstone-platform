# Capstone Backlog

> Brief §10.2 format. 15 stories, every layer L0–L6 covered, every Definition of Done names a test or an observable
> check. Owners are the roles in `docs/TEAM-GUIDE.md` §1 (A · B · C); names are in `docs/TEAM-CHARTER.md`.
> Estimate scale: **S** ≤ 1 h · **M** ≤ 2 h · **L** ≤ 4 h. A story that grew past 4 h was split into one PR per step
> (TEAM-GUIDE §2); the story keeps one DoD.
>
> **Scope-cutting order if late (Brief §6):** stretch goals → Bonus "Could" items → FR-14 → FR-13 → FR-12 →
> reduce Bonus Core. **Never cut:** tests, security rules, the compensation path, deployment.

## Status at Gate G4

| ID | Story (short) | Owner | Gate | Est. | Status |
|---|---|---|---|---|---|
| L0-01 | Platform foundations and L0 check | B | S25 | M | ✅ Done |
| L1-01 | Gateway security, Keycloak realm, rate limit | A | G1 | L | ✅ Done |
| L1-02 | Product catalogue, read model, cache | A | G1 | L | ✅ Done |
| L2-01 | Inventory stock API | B | G1 | M | ✅ Done |
| L2-02 | Place order with a resilient stock pre-check | B | G1 | L | ✅ Done |
| L2-03 | Payment exactly once per order | C | G1 | L | ✅ Done |
| L3-01 | Inventory Saga participant and NFR-05 sweeper | B | G2 | L | ✅ Done |
| L3-02 | Payment Saga step, outbox, DLT | C | G2 | L | ✅ Done |
| L3-03 | Order Saga side: outbox, consumers, retry→DLT | B | G2 | L | ✅ Done |
| L3-04 | Notification with retry and DLT | A | G2 | M | ✅ Done |
| L4-01 | Images and CI pipeline | C | G2 | L | ⚠️ Built; CI has not run on `main` yet |
| L4-02 | Kubernetes, Helm, ArgoCD | A + C | G2 | L | ⚠️ Manifests done; live Synced/Healthy not yet shown |
| L5-01 | One trace across HTTP and Kafka, JSON logs | B | G3 | M | ✅ Done |
| L5-02 | Load tests, bottleneck, Performance Report | A | G3 | L | ✅ Done (20-VU rerun open) |
| L6-01 | Bonus B2 — Order Analytics | C | G4 | L | ✅ Done |

Open items with owners are listed in `docs/FINAL-REPORT.md` §6.

---

## Stories

### L0-01 — Platform foundations and L0 check
`ID: L0-01   Layer: L0   Gate: S25   Owner: B   Estimate: M`
As a **team member**, I want the infrastructure, config-server and eureka-server to start with one command and be
checked automatically, so that nobody builds on a broken base.
**Definition of Done:** `docker compose -f deployment/docker/docker-compose.yml up -d` then `scripts/verify-l0.sh`
prints `L0 GREEN` (infra healthy, config-server serves every service file, eureka UP with running services
registered). `ConfigServerApplicationTests`, `EurekaServerApplicationTests` green.
**Depends on:** —

### L1-01 — Gateway security, Keycloak realm, rate limit
`ID: L1-01   Layer: L1   Gate: G1   Owner: A   Estimate: L`
As a **shop owner**, I want every protected route to reject missing or invalid tokens and wrong roles, and public
reads to be rate limited, so that only the right people change data and one client cannot starve the others.
**Definition of Done:** `GatewaySecurityTest` (401 without token, 403 wrong role, JSON problem body), `RateLimitIT`
(429 + `Retry-After`); curl: `POST /api/v1/products` without token → `401` (FR-04, FR-13).
**Depends on:** L0-01

### L1-02 — Product catalogue, read model, cache
`ID: L1-02   Layer: L1   Gate: G1   Owner: A   Estimate: L`
As a **shopper**, I want to browse a paginated catalogue with category names without logging in, and as an
**admin** I want to manage products, so that the shop has something to sell.
**Definition of Done:** `ProductControllerTest`, `ProductRepositoryIT` (Flyway on PostgreSQL), `ProductCacheIT`
(item and page keys evicted on every write); curl: `GET /api/v1/products?page=0&size=10` → `200` (FR-01–03, FR-15).
**Depends on:** L1-01

### L2-01 — Inventory stock API
`ID: L2-01   Layer: L2   Gate: G1   Owner: B   Estimate: M`
As an **admin**, I want to read and adjust stock per product, and order-service needs to ask "is there enough
stock?", so that orders are only accepted for goods we have.
**Definition of Done:** `InventoryControllerTest` (200, 400, 404 problem body, 401 without token, 403 for
CUSTOMER), `StockRepositoryTest` (atomic reserve never goes negative); curl as ADMIN:
`GET /api/v1/inventory/1` → `200 {available, reserved}` (FR-12).
**Depends on:** L0-01

### L2-02 — Place order with a resilient stock pre-check
`ID: L2-02   Layer: L2   Gate: G1   Owner: B   Estimate: L`
As a **customer**, I want my order answered at once with an id and `PENDING`, or a clear `409`/`503`, so that I am
never left waiting and never charged for goods that do not exist.
**Definition of Done:** `OrderSyncIntegrationTest`: `201 PENDING` priced by product-service; `409 OUT_OF_STOCK` with no
order row; `503 STOCK_CHECK_UNAVAILABLE` on 500s and on a 3 s inventory (Feign timeout); a sold-out run keeps the
circuit `CLOSED`; repeated failures open it and later orders fail fast; a full bulkhead rejects at once.
`ServiceTokenIntegrationTest`: the check carries the order-service client-credentials token (FR-14).
`OrderControllerTest`: customers read only their own orders (FR-05, FR-06, FR-10, FR-14).
**Depends on:** L2-01, L1-02

### L2-03 — Payment exactly once per order
`ID: L2-03   Layer: L2   Gate: G1   Owner: C   Estimate: L`
As a **customer**, I want a retried payment request never to charge me twice, so that network retries are safe.
**Definition of Done:** `PaymentIdempotencyIT` (same `Idempotency-Key` → stored response replayed, different body →
`422`), `PaymentControllerTest` (401/403/400/201/404/409) (FR-08).
**Depends on:** L0-01

### L3-01 — Inventory Saga participant and NFR-05 sweeper
`ID: L3-01   Layer: L3   Gate: G2   Owner: B   Estimate: L`
As the **platform**, I want stock reserved on `OrderPlaced` and released on `PaymentFailed`/`OrderCancelled`, so that
a failed order never keeps stock.
**Definition of Done:** `InventoryServiceTest` (reserve; not enough stock → `InventoryReservationFailed`; release; the
sweeper keeps an order still awaiting payment; two confirming events consume the stock once),
`ReservationSweeperTest`, `InventorySagaDeadLetterIT`; NFR-05 query (README "Orders & Saga") returns `0` (FR-07, NFR-05).
**Depends on:** L2-01, L3-03

### L3-02 — Payment Saga step, outbox, DLT
`ID: L3-02   Layer: L3   Gate: G2   Owner: C   Estimate: L`
As the **platform**, I want payment to charge on `InventoryReserved` and publish the outcome reliably, so that the
order always learns whether it was paid.
**Definition of Done:** `PaymentSagaKafkaIT` (one `PaymentCompleted` for a doubly delivered event, poison message →
`inventory-events.DLT`), `HandleInventoryReservedIT`, `DeclinedPaymentIT` (FR-08, FR-09, NFR-10).
**Depends on:** L2-03, L3-01

### L3-03 — Order Saga side: outbox, consumers, retry→DLT
`ID: L3-03   Layer: L3   Gate: G2   Owner: B   Estimate: L`
As a **customer**, I want my order to become `CONFIRMED` after payment or `CANCELLED` with my stock released after a
failure, and never to be lost, so that the status I poll is the truth.
**Definition of Done:** `OutboxPublisherTest` (pending rows published with `traceparent` + `eventType` headers, then marked `SENT`),
`OrderKafkaListenerTest`, `OrderServiceTest` (CONFIRMED/CANCELLED, repeated outcome is a no-op, conflict rejected),
`OrderSagaDeadLetterIT` (retry then succeed, retry then DLT, conflict straight to DLT), `PendingOrderSweeperTest`
(stuck order cancelled after 10 min). Live: payment-failure demo ends `CANCELLED` with stock back (FR-09, NFR-01, NFR-10).
**Depends on:** L2-02, L3-02

### L3-04 — Notification with retry and DLT
`ID: L3-04   Layer: L3   Gate: G2   Owner: A   Estimate: M`
As a **customer**, I want a confirmation on `CONFIRMED` and a notice on `CANCELLED`, and as **operations** I want
failed sends parked, so that a broken mail provider never blocks orders.
**Definition of Done:** `NotificationKafkaIT.shouldRetryThenParkInDlt_whenSendKeepsFailing`,
`SendOrderNotificationServiceTest`, `OrderEventsListenerTest` (FR-11).
**Depends on:** L3-03

### L4-01 — Images and CI pipeline
`ID: L4-01   Layer: L4   Gate: G2   Owner: C   Estimate: L`
As the **team**, I want every service built, tested and published by CI, so that what we deploy is what we tested.
**Definition of Done:** CI on `main` green: tests + JaCoCo ≥ 60 % on `*.application`, gitleaks, non-root `USER`
guard; images `ghcr.io/13nour11/<service>:<sha>` in GHCR (NFR-04, NFR-07).
**Depends on:** L0-01

### L4-02 — Kubernetes, Helm, ArgoCD
`ID: L4-02   Layer: L4   Gate: G2   Owner: A (Helm, ArgoCD) + C (cluster, Secrets)   Estimate: L`
As the **team**, I want the platform to run in a cluster from Git, so that a stranger can deploy it and drift is
reverted.
**Definition of Done:** `kubectl -n ecommerce get pods` all `Ready` (probes), Secrets from
`scripts/create-k8s-secrets.sh`, one ServiceAccount per service; `argocd app list` → all `Synced/Healthy` (NFR-08).
**Depends on:** L4-01

### L5-01 — One trace across HTTP and Kafka, JSON logs
`ID: L5-01   Layer: L5   Gate: G3   Owner: B   Estimate: M`
As **operations**, I want one `traceId` from the gateway to the notification, and log lines I can search by it, so
that "where is order X and why?" has one answer.
**Definition of Done:** Zipkin shows one trace gateway → order → `order-events` → inventory → payment → order →
notification; `docker compose logs | grep '"traceId":"<id>"'` returns the JSON lines of that order in every service;
`OutboxPublisherTest` asserts the `traceparent` header (NFR-06).
**Depends on:** L3-03

### L5-02 — Load tests, bottleneck, Performance Report
`ID: L5-02   Layer: L5   Gate: G3   Owner: A   Estimate: L`
As the **team**, I want measured latency and throughput and one bottleneck fixed, so that our NFR claims have numbers.
**Definition of Done:** `k6/smoke-test.js`, `load-test.js`, `stress-test.js` results in `docs/PERFORMANCE-REPORT.md`;
before/after A/B for the fix (`POST /orders` p95 600 → 443 ms) (NFR-02, NFR-03).
**Depends on:** L3-03

### L6-01 — Bonus B2 — Order Analytics
`ID: L6-01   Layer: L6   Gate: G4   Owner: C   Estimate: L`
As **operations (ADMIN)**, I want order volume, revenue and Saga failure rate without querying databases, so that I
see problems as they happen.
**Definition of Done:** `OrderAnalyticsProjectorIT` (a redelivered event never double counts),
`AnalyticsControllerTest` (`GET /api/v1/analytics/summary`, ADMIN only), Grafana *Order Analytics* with ≥ 3 panels
(FR-16).
**Depends on:** L3-03
