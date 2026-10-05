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

### Bonus B2 — Order Analytics (primary Bonus)

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

### Bonus B1 — Product Reviews & Ratings (extra Bonus)

**Owns:** new `review-service` (:8086) with its own `review_db` (`reviews`, `outbox_event`), the `review-events` topic,
and in product-service the read model `product_review_ratings` (one row per review). **Does not own:** products,
customers, orders.

| | |
|---|---|
| **Decision B1-1** | Ratings reach the product detail through an event (`ReviewSubmitted` → product-service read model); the average and count are computed at read time from one stored row per review. |
| **Options** | (a) Event + per-review rows. (b) Event + incremental `sum/count` columns. (c) product-service calls review-service on every read. |
| **Reason** | (a) cannot drift: a redelivered event hits the `review_id` primary key and changes nothing. (b) double-counts on redelivery. (c) couples the public catalogue's latency to a second service. |
| **Trade-off** | The rating is eventually consistent (outbox poll ≤ 500 ms + consumer). Each product read runs two correlated sub-queries; the result is cached with the product. |
| **Revisit when** | Reviews per product reach the thousands and the sub-queries show up in the product P95 — then keep a summary table updated in the same consumer transaction. |

### Bonus B3 — Multi-Tenant Gateway (extra Bonus)

**Owns:** tenant resolution at the gateway, the `tenant_id` claim (Keycloak user attribute + mapper), the
`X-Tenant-Id` header, and tenant isolation of product data. **Does not own:** orders, stock and payments stay
global (the Brief scopes isolation to Product data).

| | |
|---|---|
| **Decision B3-1** | Tenant = the JWT claim `tenant_id`. Anonymous catalogue reads may choose a whitelisted tenant with `X-Tenant-Id`, else `tenant-a`. A header that differs from the claim is `403 TENANT_MISMATCH`; an unknown tenant is `400`. |
| **Options** | (a) JWT claim. (b) Host name / sub-domain. (c) Client-sent header only. |
| **Reason** | (a) cannot be forged by the client and needs no DNS. (c) alone would let any caller act for any tenant. |
| **Trade-off** | Anonymous users can read either public catalogue by choosing the header — acceptable, both catalogues are public. |
| **Revisit when** | Tenants get their own host names, or a tenant's catalogue must not be public. |

| | |
|---|---|
| **Decision B3-2** | A `tenant_id` column on `products`, filtered explicitly in every repository query (`... where p.tenantId = :tenant`); writes use `findByIdAndTenantId`, so another tenant's product is `404`, never `403`. |
| **Options** | (a) Explicit filter in each query. (b) Hibernate `@TenantId` discriminator. (c) Schema or database per tenant. |
| **Reason** | (a) is visible in code review and tested query by query. (c) needs new infrastructure. |
| **Trade-off** | A new query that forgets the filter leaks data — mitigated by the tests below and by banning native SQL on `products`. |
| **Revisit when** | More tenant-owned tables appear; then (b) removes the repetition. |

**Every place the tenant id must appear** (Brief: list them all): (1) Keycloak user attribute `tenant_id` + mapper on
client `api-gateway`; (2) gateway `TenantFilter` → `X-Tenant-Id`; (3) gateway rate-limit key `tenant:<t>:user|ip:...`
(per-tenant limit); (4) product `TenantResolver` (claim first, then header); (5) `products.tenant_id` column (V3);
(6) every `ProductRepository` query; (7) Redis cache keys `product::<tenant>:<id>` and `products::<tenant>:<page>...`.
Tests: `GatewaySecurityTest` (resolution, mismatch, unknown), `ProductRepositoryIT` and `ProductCacheIT`
(tenant A cannot read, update or delete B's product, through the database or the cache).

### Bonus B4 — Real-Time Inventory Alerts (extra Bonus)

**Owns:** the `LowStock` event on topic `inventory-alerts`, the `stock.low_stock_alerted` flag, the low-stock query
`GET /api/v1/inventory/low-stock`, and the SSE stream `GET /api/v1/alerts/stream` in notification-service.

| | |
|---|---|
| **Decision B4-1** | Server-Sent Events from notification-service through the gateway (route without response timeout or rate limit), admin client = any SSE client sending the Bearer header (`curl -N`, `fetch`). |
| **Options** | (a) SSE. (b) WebSocket. (c) Polling the low-stock query. |
| **Reason** | Alerts flow one way, server → admin; SSE is plain HTTP, passes the gateway's JWT check at connect time and reconnects by itself. (c) cannot meet "within 2 seconds" without hammering the API. |
| **Trade-off** | The token is checked when the stream opens; a stream outlives the 5-minute token until the client reconnects. |
| **Revisit when** | Admins must also send commands (acknowledge) over the same channel — then WebSocket. |

| | |
|---|---|
| **Decision B4-2** | Deduplicate per product at the source: inventory publishes `LowStock` only on the NORMAL → LOW transition (`low_stock_alerted` flag, reset when stock is back at or above the threshold), in the same transaction as the stock change (outbox). notification-service also drops a redelivered event id. |
| **Options** | (a) Transition flag at the source. (b) Deduplicate only in the consumer. |
| **Reason** | (a) is correct across restarts and replicas; (b) alone forgets after a restart. |
| **Trade-off** | One extra column; the threshold is global (`inventory.low-stock.threshold`, default 5). |
| **Revisit when** | Products need individual thresholds (add a column) or notification-service runs two replicas (consumer group per pod so every replica broadcasts). |

---

## 3. API contract + events

<!-- Owner: B (events) · each service owner for its endpoints. Frozen on Day 1; changes need every consumer's approval. -->

**Versioning.** Every public API is under `/api/v1`. A breaking change gets `/api/v2` next to `/v1`. Events evolve by
adding fields only (consumers ignore unknown fields); a breaking change gets a new event name.

**Errors.** RFC 7807 `ProblemDetail` with a stable `code` (`VALIDATION_ERROR`, `OUT_OF_STOCK`,
`STOCK_CHECK_UNAVAILABLE`, `ORDER_NOT_FOUND`, `PRODUCT_NOT_FOUND`, `IDEMPOTENCY_KEY_REUSED`, `REFUND_NOT_ALLOWED`,
`DUPLICATE_REVIEW`, `UNAUTHORIZED`, `FORBIDDEN`, `RATE_LIMITED`, `TENANT_MISMATCH`, `UNKNOWN_TENANT`). No stack traces.

### 3.1 Endpoints

| Service | Method · path | Request | Response | Errors | Access |
|---|---|---|---|---|---|
| product | `GET /api/v1/products?page&size(≤50)&sort` | — | page of `{id,name,description,price,categoryId,categoryName,averageRating,ratingCount}` | 400 | public (rate limited) |
| product | `GET /api/v1/products/{id}` | — | one product (same fields) | 404 | public |
| product | `POST` / `PUT /api/v1/products[/{id}]`, `DELETE /{id}` | `{name,description,price>0,categoryId}` | 201 + Location / 200 / 204 | 400, 404 | ADMIN |
| order | `POST /api/v1/orders` | `{items:[{productId,quantity>0,unitPrice>0}]}` | 201 `{orderId,customerId,totalAmount,status:PENDING,items,createdAt}` | 400, 409 `OUT_OF_STOCK`, 503 `STOCK_CHECK_UNAVAILABLE` | CUSTOMER |
| order | `GET /api/v1/orders/{id}` · `GET /api/v1/orders` | — | one order · own orders | 404 (also for another customer's order) | CUSTOMER |
| order (B2) | `GET /api/v1/analytics/summary?hours=1..168` | — | counts per status, revenue, cancelled ratio, hourly rows | 400 | ADMIN |
| inventory | `GET /api/v1/inventory/check?productId&quantity` | — | `{productId,requestedQuantity,available}` | 400 | SERVICE (not routed) |
| inventory | `GET` / `PUT /api/v1/inventory/{productId}` | `{availableQuantity≥0}` | `{productId,available,reserved}` | 404, 400 | ADMIN |
| inventory (B4) | `GET /api/v1/inventory/low-stock` | — | `[{productId,available,reserved}]` | — | ADMIN |
| payment | `POST /api/v1/payments` + `Idempotency-Key` | `{orderId,amount}` | 201 / replay | 400, 422 | SERVICE, ADMIN |
| payment | `POST /api/v1/payments/{id}/refund` | — | payment | 404, 409 | ADMIN |
| review (B1) | `POST /api/v1/products/{id}/reviews` | `{rating 1..5, comment}` | 201 review | 400, 409 `DUPLICATE_REVIEW` | CUSTOMER |
| review (B1) | `GET /api/v1/products/{id}/reviews?page&size(≤50)` | — | page of reviews | 400 | public |
| notification (B4) | `GET /api/v1/alerts/stream` | — | `text/event-stream`, events `low-stock` | — | ADMIN |

### 3.2 Event contract

**Decision 3-1 — event format.**
- **DECISION:** a flat JSON object per event (the Java record), Kafka key = `orderId` (product id for `LowStock` and
  `ReviewSubmitted`), headers `eventType`, `eventId` and `traceparent`. Consumers route on the `eventType` header and
  deduplicate on `eventId`.
- **OPTIONS:** (a) flat payload + headers; (b) an envelope `{eventId,eventType,eventVersion,payload}` in the body.
- **REASON:** order, inventory and payment were built on (a); one consumer (notification) used (b), so its events were
  rejected. Converging on (a) changed one consumer instead of three producers and four consumers.
- **TRADE-OFF:** no `eventVersion` field; evolution is additive only (see Versioning).
- **REVISIT:** the first breaking change to an event — add an `eventVersion` header.

| Event | Topic | Producer | Consumers | Fields |
|---|---|---|---|---|
| `OrderPlaced` | order-events | order | inventory, order-analytics (B2) | eventId, orderId, customerId, totalAmount, items[{productId,quantity,unitPrice}], occurredAt |
| `OrderConfirmed` | order-events | order | inventory (consume reservation), notification, order-analytics | eventId, orderId, customerId, occurredAt |
| `OrderCancelled` | order-events | order | inventory (release), notification, order-analytics | eventId, orderId, customerId, reason, occurredAt |
| `InventoryReserved` | inventory-events | inventory | payment | eventId, orderId, customerId, totalAmount, occurredAt |
| `InventoryReservationFailed` | inventory-events | inventory | order (cancel) | eventId, orderId, reason, occurredAt |
| `InventoryReleased` | inventory-events | inventory | — (catalogue/compensation record; order is already cancelled by `PaymentFailed`) | eventId, orderId, reason, occurredAt |
| `PaymentCompleted` | payment-events | payment | order (confirm), inventory (consume) | eventId, orderId, paymentId, totalAmount, occurredAt |
| `PaymentFailed` | payment-events | payment | order (cancel), inventory (release) | eventId, orderId, reason, occurredAt |
| `LowStock` (B4) | inventory-alerts | inventory | notification (SSE) | eventId, productId, available, threshold, occurredAt |
| `ReviewSubmitted` (B1) | review-events | review | product (rating read model) | eventId, reviewId, productId, rating, occurredAt |

Dead letters: `<topic>.DLT` (notification uses `order-events.retry-N` → `order-events.DLT`).

---

## 4. Data model

<!-- Owner: C -->

Database per service on one PostgreSQL instance (Brief §5). Schemas are created **only** by Flyway; every service runs `ddl-auto=validate`.

| Service | Database | Tables (migration) | Owner |
|---|---|---|---|
| product-service | `product_db` | `categories`, `products` (V1, seed V2); `products.tenant_id` (B3) + `product_review_ratings` (B1) (V3) | A |
| order-service | `order_db` | `orders`, `order_items` (V1), `outbox_event` (V2), `processed_event` (V3) | B |
| order-service — B2 | `order_db` | `analytics_order`, `analytics_processed_event` (`V50__create_order_analytics`) | C |
| inventory-service | `inventory_db` | `stock`, `reservation` (V1), `outbox_event`, `processed_event` (V2); one reservation per order line, `cancelled_order`, `stock.low_stock_alerted` (V3) | B |
| payment-service | `payment_db` | `payments`, `idempotency_keys`, `processed_event`, `outbox_event` (`V1__create_payment_tables`) | C |
| review-service (B1) | `review_db` | `reviews` (UNIQUE `product_id, customer_id`), `outbox_event` (`V1__create_review_tables`) | — |
| notification-service | — | none; failed sends go to `<topic>.DLT` | A |

Each database has its own login (`product_user`, `order_user`, …) that owns only that database
(`deployment/docker/postgres/init-databases.sh`); no service can read another service's tables.

### Inventory tables

| Table | Key | Purpose |
|---|---|---|
| `stock` | `product_id` | `available`, `reserved`, `version`, `low_stock_alerted` (B4). Reserve is one conditional `UPDATE ... WHERE available >= :q` — no oversell, no lock held across calls. |
| `reservation` | `id`; **`UNIQUE(order_id, product_id)`** | One row per order line: release and confirm touch exactly the reserved products (FR-07). Status `RESERVED → CONSUMED | RELEASED`. |
| `cancelled_order` | `order_id` | Orders known to be cancelled; the NFR-05 sweeper releases any reservation still RESERVED for them, and a late `OrderPlaced` reserves nothing. |

**NFR-05 evidence query** (must return 0):
```sql
SELECT r.order_id, r.product_id FROM reservation r JOIN cancelled_order c ON c.order_id = r.order_id
WHERE r.status = 'RESERVED' AND c.cancelled_at < now() - interval '30 seconds';
```

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
- B's `V1`–`V3` were all merged before any shared database ran `V50`, so no `out-of-order` setting is needed. A future
  B migration (`V4`…) on a database that already ran `V50` would need `spring.flyway.out-of-order: true`.
- Fresh databases (CI, Testcontainers, a new compose volume) apply versions in order.

---

## 5. Communication

<!-- Owner: B -->

| Interaction | Style | Why |
|---|---|---|
| Client → gateway → services | sync HTTP (REST) | the client waits for an answer |
| order → inventory `/check` | sync HTTP (OpenFeign via Eureka) + Resilience4j | the caller must know *now* whether to accept the order (FR-06) |
| order → inventory → payment → order → notification | async Kafka (Saga) | the business transaction; the customer does not wait (gets `PENDING` + `orderId`) |
| inventory → notification (B4), review → product (B1), order → analytics (B2) | async Kafka | read models and alerts; producers must not depend on consumers |

**Decision 5-1 — Saga style.**
- **DECISION:** Choreography: each participant reacts to the previous event. Order status + one trace per order answer
  "where is order X?".
- **OPTIONS:** (a) Choreography; (b) Orchestration with a `SagaState` table in order_db.
- **REASON:** three participants, a linear flow and no branching rules (Brief decision guide); no coordinator to keep alive.
- **TRADE-OFF:** the flow is spread over three services; understanding it needs the event table above and Zipkin.
- **REVISIT:** a branching rule appears (fraud check, partial refunds, loyalty) — then Orchestration, state in PostgreSQL.

**Decision 5-2 — publishing.**
- **DECISION:** Transactional Outbox in every producer (order, inventory, payment, review): the event row is written in
  the business transaction; a poller (`FOR UPDATE SKIP LOCKED`, every 500 ms) sends it and marks it published only
  after the broker acknowledged it. Consumers are idempotent (`processed_event` / unique keys).
- **OPTIONS:** (a) outbox + polling; (b) direct `kafkaTemplate.send()` after commit.
- **REASON:** (b) loses the event when the process dies between commit and send.
- **TRADE-OFF:** up to ~1 s extra latency and at-least-once duplicates (absorbed by idempotent consumers).
  **Failure window accepted:** none for loss; duplicates only.
- **REVISIT:** latency budget below 1 s for a Saga step — then CDC (Debezium) instead of polling.

**Decision 5-3 — resilience of the sync call.**
- **DECISION:** `Retry(CircuitBreaker(TimeLimiter(Bulkhead(call))))` on order → inventory (Retry 2 × 500 ms,
  CB 50 % of 5 calls, open 5 s, TimeLimiter 2 s, Bulkhead 20). The guarded call returns "in stock?" as a value, so an
  out-of-stock answer is never a failure. The fallback is **503 `STOCK_CHECK_UNAVAILABLE`**; the order is not accepted.
- **REASON:** accepting an order optimistically would break FR-06 ("rejected without touching payment").
- **TRADE-OFF:** while inventory is down, no order can be placed.
- **REVISIT:** if the business prefers accepting orders and compensating later.

**Order of events (happy path):** `OrderPlaced` → `InventoryReserved` → `PaymentCompleted` → order `CONFIRMED` →
`OrderConfirmed` → notification + inventory consumes the reservation.
**Payment fails:** `PaymentFailed` → order `CANCELLED` (`OrderCancelled` → cancel notice) and, in parallel,
inventory releases the stock → `InventoryReleased`.
**No stock at reserve time:** `InventoryReservationFailed` → order `CANCELLED`.

---

## 6. Failure modes

<!-- Owner: B -->

| # | What fails | Detection | What the user sees | Mitigation / recovery |
|---|---|---|---|---|
| 1 | Not enough stock | inventory `/check` answers `available=false` | 409 `OUT_OF_STOCK` | no order row, payment never touched (FR-06) |
| 2 | Inventory slow | TimeLimiter (2 s), `resilience4j_*` metrics | 503 `STOCK_CHECK_UNAVAILABLE` | retry once, then fallback; nothing persisted (`OrderSyncIntegrationTest`) |
| 3 | Inventory down | circuit breaker OPEN | fast 503, no hang | half-open probe after 5 s recovers by itself |
| 4 | Payment declined | `PaymentFailed`, `payments` row FAILED | order CANCELLED + cancel notice | inventory releases the stock, `InventoryReleased` (compensation) |
| 5 | Payment service down | consumer lag on `inventory-events` | order stays PENDING | events wait in Kafka; on restart the order completes. Reservations of PENDING orders are **not** released by the sweeper (NFR-01) |
| 6 | Same payment request twice | `Idempotency-Key` hit | the same response | one charge; same key + other body → 422 (FR-08) |
| 7 | Event delivered twice | `processed_event` / unique key hit | nothing | no second reserve, charge, release, rating or count (NFR-10) |
| 8 | Consumer keeps failing / poison message | retry metrics, `<topic>.DLT` | delayed or no notice | retried, then parked in the DLT with an alert log (FR-11) |
| 9 | Kafka down | outbox backlog grows (`outbox_pending`, PENDING rows) | orders still accepted (PENDING) | the poller drains the outbox when Kafka is back; nothing is lost |
| 10 | Redis down | cache errors logged | slower product reads, still 200 | `CacheErrorHandler` falls back to the DB; the rate limiter fails open |
| 11 | Missing / expired / forged JWT | 401 count at the gateway | 401 JSON | no downstream call (FR-04) |
| 12 | Rate limit exceeded | 429 count | 429 `RATE_LIMITED` + `Retry-After` | per-client (per-tenant) bucket refills |
| 13 | Order cancelled but a reservation survives | NFR-05 query (§4) | nothing | release on `PaymentFailed`/`OrderCancelled` at once; sweeper every 10 s for cancelled orders; late `OrderPlaced` for a cancelled order reserves nothing |

**Risks raised in the S25 Architecture Review:** *to be filled in by the team* — the review's two risks are not
recorded in the repository. Rows #5 (payment down) and #7 (duplicate delivery) cover the failure paths the Brief names.

---

## 7. Security & deployment

<!-- Owner: A -->

### 7.1 Roles per endpoint (enforced at the gateway; product, inventory, payment and review check again)

| Endpoint | Access | Enforced by |
|---|---|---|
| `GET /api/v1/products`, `GET /api/v1/products/{id}` | public (rate limited) | gateway `permitAll` + `RequestRateLimiter`; product-service `permitAll` |
| `POST/PUT/DELETE /api/v1/products[/{id}]` | `ADMIN` | gateway `hasRole(ADMIN)`; product-service `@PreAuthorize` |
| `POST /api/v1/orders`, `GET /api/v1/orders[/{id}]` | `CUSTOMER` (own orders only) | gateway `hasRole(CUSTOMER)`; order-service owner check |
| `GET /api/v1/inventory/check` | service (`SERVICE` role, client credentials) | **not exposed**: gateway `denyAll`; inventory-service checks the role |
| `GET/PUT /api/v1/inventory/{productId}`, `GET /api/v1/inventory/low-stock` | `ADMIN` | gateway + inventory-service |
| `/api/v1/payments/**` | `ADMIN` (refund) / `SERVICE`,`ADMIN` (charge) | gateway + payment-service |
| `/api/v1/analytics/**` (B2), `/api/v1/alerts/**` (B4) | `ADMIN` | gateway |
| `GET /api/v1/products/{id}/reviews` · `POST` (B1) | public · `CUSTOMER` | gateway + review-service |
| `/actuator/health/**`, `/actuator/prometheus` | internal | not routed by the gateway; cluster network only |
| any other path | denied | gateway `anyExchange().denyAll()` |

401/403/429 bodies follow the same RFC 7807 contract as service errors (`code` = `UNAUTHORIZED` / `FORBIDDEN` / `RATE_LIMITED`).

**D7.1 — Where JWTs are validated**
- **DECISION:** the gateway validates every token (NFR-04). product, inventory, payment and review-service are also
  Resource Servers and check roles themselves. order-service and notification-service rely on the gateway: order reads
  the customer from `X-User-Id` (set by the gateway from the JWT, never defaulted), notification serves only the
  ADMIN alert stream behind the gateway.
- **OPTIONS:** (a) gateway only, services trust `X-User-*`; (b) gateway + every service.
- **REASON:** the services that hold other services' trust (inventory `/check`, payment) or public write paths
  validate again; order and notification were built on the gateway contract and the Brief requires validation at the gateway.
- **TRADE-OFF:** inside the cluster, a pod that can reach order-service directly can spoof `X-User-Id`. Accepted for the
  Capstone; the services are not exposed outside the cluster network.
- **REVISIT:** before any shared or production cluster: make order-service a Resource Server (customer id = `sub`) or
  add NetworkPolicies so only the gateway can reach it.

**D7.2 — Identity headers**
- **DECISION:** the gateway removes every incoming `X-User-*` header, then sets `X-User-Id` (sub) and `X-User-Roles` from the validated token.
- **REASON:** downstream code and logs get the identity without parsing JWTs, and a client cannot inject an identity.
- **TRADE-OFF:** the headers are convenience only; authorization decisions still use the JWT (D7.1).
- **REVISIT:** never relax the stripping. B3 adds `X-Tenant-Id` the same way (resolved from the token, see §2).

**D7.3 — Token issuer in every environment**
- **DECISION:** Keycloak runs with a fixed `KC_HOSTNAME=http://localhost:8180`; services check `iss` against it and fetch keys from the in-network address (`KEYCLOAK_JWK_SET_URI`).
- **OPTIONS:** (a) `issuer-uri` discovery from each service; (b) fixed hostname + separate JWKS URI.
- **REASON:** with (a), a token fetched from the host (`localhost:8180`) and validated inside Docker/Kubernetes (`keycloak:8180`) fails with an `iss` mismatch — every call becomes 401.
- **TRADE-OFF:** the public hostname is configuration that must match in Compose, kind and the Helm values.
- **REVISIT:** when an ingress hostname exists for Keycloak, set `KC_HOSTNAME` and `KEYCLOAK_ISSUER_URI` to it.

**D7.4 — Rate limiting (FR-13)**
- **DECISION:** Redis `RequestRateLimiter` on the public product and review routes, one token bucket per user id, else per client IP, inside the tenant (20 req/s, burst 40, configurable).
- **REASON:** shared across gateway replicas; Redis is already in the stack (no new infrastructure).
- **TRADE-OFF:** if Redis is down the limiter **fails open** (requests pass) — availability over protection. All anonymous clients behind one NAT share a bucket.
- **REVISIT:** abuse in production → fail closed for anonymous traffic, or limit at the ingress.

### 7.2 Token propagation

| Call | Credential |
|---|---|
| Client → gateway → service | the user's bearer JWT, forwarded unchanged (product, inventory, payment and review validate it again), plus `X-User-Id`, `X-User-Roles`, `X-Tenant-Id` set by the gateway |
| order-service → inventory-service `/check` (Feign) | **client credentials** of confidential client `order-service` (realm role `SERVICE`, FR-14), not the user's token |
| Saga events (Kafka) | no token: the broker is internal; identity fields (customer id) are copied into the event payload |

### 7.3 Secrets (NFR-04)

| Secret | Local (Compose) | Kubernetes | CI |
|---|---|---|---|
| DB passwords, Keycloak admin, `order-service` client secret, test-user password | git-ignored `.env` (template `.env.example`) | Secret `<service>-secrets` from `scripts/create-k8s-secrets.sh`, mounted with `envFrom` | — |
| GHCR push | — | `imagePullSecrets` (if packages are private) | GitHub Actions `GITHUB_TOKEN` |
| Leak guard | — | — | `gitleaks` job on every push and PR |

The realm export contains no secret: `${ORDER_SERVICE_CLIENT_SECRET}` and `${KC_TEST_USER_PASSWORD}` are resolved
from the environment at import. Services read passwords only from environment variables.

### 7.4 Images, probes, Helm, ArgoCD

| Concern | Decision |
|---|---|
| Image | one template for all services: multi-stage (`maven:3.9-eclipse-temurin-21` → `eclipse-temurin:21-jre-alpine`), layered jar, `USER 10001`, `HEALTHCHECK` on `/actuator/health`, `MaxRAMPercentage=75` + SerialGC |
| Probes | `startupProbe` (≤ 3 min), `livenessProbe` `/actuator/health/liveness`, `readinessProbe` `/actuator/health/readiness` |
| Pod hardening | `runAsNonRoot` (10001), `readOnlyRootFilesystem` + `/tmp` emptyDir, no privilege escalation, drop `ALL`, seccomp `RuntimeDefault` |
| Least privilege | one ServiceAccount per service, no RBAC bindings, `automountServiceAccountToken: false` |
| Resources | requests 100m / 256Mi, limit 512Mi per service |

**D7.5 — Helm packaging**
- **DECISION:** one reusable chart (`deployment/helm/microservice`) + one values file per service; infrastructure as raw manifests.
- **OPTIONS:** (a) one chart per service; (b) one shared chart; (c) raw manifests everywhere.
- **REASON:** the services differ only in image, port, env and Secret name; one chart keeps hardening identical everywhere (Brief: Helm for ≥ 2 services — here all 9, each its own release).
- **TRADE-OFF:** a service that needs something unusual (e.g. a PVC) needs a new chart option or its own chart.
- **REVISIT:** when one service's needs diverge enough that the shared template grows conditionals for it.

**D7.6 — GitOps**
- **DECISION:** ArgoCD `infra` Application + `services` ApplicationSet, automated `prune` + `selfHeal`, tracking branch `env/dev`; CI merges `main` into it and commits the new `:sha` tags (never a force-push).
- **OPTIONS:** (a) `helm upgrade` from CI; (b) ArgoCD tracking `main`; (c) ArgoCD tracking `env/dev`.
- **REASON:** drift is detected and reverted (demo: `kubectl scale` is undone); `main` stays protected because CI never pushes to it.
- **TRADE-OFF:** two branches to understand; a bad tag bump reaches the cluster without review (mitigated by the CI test gate).
- **REVISIT:** more environments → one `env/<name>` branch or overlay per environment.

**D7.7 — Observability.** Micrometer Tracing (Brave) → Zipkin at sampling 1.0; Kafka template and listener observation on,
so `traceparent` rides in the record headers; the outboxes store the trace of the writing transaction and continue it
when they publish, so one traceId spans gateway → order → Kafka → inventory → payment → order → notification.
Logs are JSON (ECS) with `traceId`/`spanId`. Prometheus scrapes `/actuator/prometheus`; Grafana provisions the dashboards.

**Replaced Defaults:** none. Eureka, Config Server, Keycloak Resource Server, Helm and ArgoCD are all kept as in the Brief.

---

## 8. Test & load plan + risks

<!-- Owner: C -->

### Test levels

| Level | Tool | Evidence |
|---|---|---|
| Unit | JUnit 5 + Mockito | service, domain, listener and outbox tests in every module (e.g. `InventoryServiceTest`, `OrderServiceTest`, `PaymentTest`, `AlertBroadcasterTest`, `ReviewEventsListenerTest`) |
| Web slice | `@WebMvcTest` / `WebTestClient` + `spring-security-test` | `GatewaySecurityTest` (role matrix, tenant rules), `ProductControllerTest`, `InventoryControllerTest`, `OrderControllerTest`, `PaymentControllerTest`, `ReviewControllerTest`, `AnalyticsControllerTest` |
| Integration (Testcontainers) | PostgreSQL 16, Redis 7, Kafka | `ProductRepositoryIT` + `ProductCacheIT` (FR-15, B1, B3), `StockRepositoryTest` (Flyway, FR-07, NFR-05), `OrderAnalyticsProjectorIT` (B2), `PaymentIdempotencyIT` (FR-08), `HandleInventoryReservedIT`, `DeclinedPaymentIT`, `SubmitReviewServiceIT` (B1), `RateLimitIT` (FR-13), `NotificationKafkaIT` (FR-11 retry → DLT, B4 < 2 s) |
| Messaging | `@EmbeddedKafka` + PostgreSQL | `PaymentSagaKafkaIT`: `InventoryReserved` → `PaymentCompleted`, redelivery ignored, poison message → DLT |
| Resilience | WireMock | `OrderSyncIntegrationTest`: 201, 409, 500 → 503, slow inventory → TimeLimiter → 503 |
| Contract | Pact (order ↔ inventory) | Recommended by the Brief, not built: the shared `CheckStockResponse` shape is covered by the WireMock tests |
| Coverage | JaCoCo | the build fails a module whose `*.application` packages are below **60 %** line coverage (NFR-07) |

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
