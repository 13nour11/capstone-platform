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

## 2. Bounded Context — Bonus B2: Order Analytics

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

### 3.1 REST surface

Everything public is under `/api/v1`. Roles are in §7.1; this table is the contract itself.

| Method + path | Request | Success | Business errors |
|---|---|---|---|
| `GET /api/v1/products` | `page`, `size` (max 50), `sort` (whitelisted) | `200` page of products | — |
| `GET /api/v1/products/{id}` | — | `200` product + `categoryName` | `404 PRODUCT_NOT_FOUND` |
| `POST /api/v1/products` | `{name, description, price, categoryId}` | `201` + `Location` | `400 VALIDATION_ERROR` |
| `PUT /api/v1/products/{id}` | same | `200`, evicts the cache | `404`, `400` |
| `DELETE /api/v1/products/{id}` | — | `204` | `404` |
| `POST /api/v1/orders` | `{items:[{productId, quantity}]}` | `201 {orderId, status: PENDING, totalAmount, items}` | `409 OUT_OF_STOCK`, `503 STOCK_CHECK_UNAVAILABLE`, `400 VALIDATION_ERROR` |
| `GET /api/v1/orders/{id}` | — | `200` order | `404 ORDER_NOT_FOUND` (also when it belongs to someone else) |
| `GET /api/v1/orders` | — | `200` the caller's own orders | — |
| `GET /api/v1/analytics/summary` | `hours` (1 to 168, default 24) | `200` totals, per-status counts, revenue, hourly rows | `400` outside the range |
| `GET /api/v1/inventory/check` | `productId`, `quantity` | `200 {productId, requestedQuantity, available}` | internal only, never routed |
| `GET /api/v1/inventory/{productId}` | — | `200 {productId, available, reserved}` | `404 PRODUCT_NOT_FOUND` |
| `PUT /api/v1/inventory/{productId}` | `{availableQuantity}` | `200` new stock | `400` |
| `POST /api/v1/payments` | header `Idempotency-Key`, `{orderId, amount}` | `201` payment; a replay returns the stored response with `Idempotent-Replayed: true` | `400` missing key, `422` same key with a different body |
| `POST /api/v1/payments/{id}/refund` | — | `200` refunded payment | `404`, `409` |

**Two rules the client cannot override.** The order's `customerId` comes from the JWT `sub`, never from a header or
the body; and the item price comes from product-service, never from the request. `OrderItemRequest` therefore carries
only `productId` and `quantity`.

**Error contract.** Every service answers with RFC 7807 `ProblemDetail` plus a stable `code`, so a client switches on
`code` and never on the prose:

```json
{ "type": "urn:problem:out-of-stock", "title": "Out of stock", "status": 409,
  "detail": "Product 4 is out of stock", "instance": "/api/v1/orders",
  "code": "OUT_OF_STOCK", "productId": 4, "timestamp": "2026-10-05T11:36:03.450726186Z" }
```

Codes in use: `VALIDATION_ERROR`, `UNAUTHORIZED`, `FORBIDDEN`, `RATE_LIMITED`, `PRODUCT_NOT_FOUND`,
`ORDER_NOT_FOUND`, `OUT_OF_STOCK`, `STOCK_CHECK_UNAVAILABLE`, `IDEMPOTENCY_KEY_REUSED`. Stack traces, SQL and secrets
never appear in a response body.

### 3.2 Event catalogue

Three topics, one per producing service. The Kafka **key is always `orderId`**, so one order's events stay ordered
on one partition.

| Event | Producer and topic | Consumers | Payload |
|---|---|---|---|
| `OrderPlaced` | order to `order-events` | inventory, analytics | `eventId, orderId, customerId, totalAmount, items[{productId, quantity, unitPrice}], occurredAt` |
| `OrderConfirmed` | order to `order-events` | notification, inventory, analytics | `eventId, orderId, customerId, occurredAt` |
| `OrderCancelled` | order to `order-events` | notification, inventory, analytics | `eventId, orderId, reason, occurredAt` |
| `InventoryReserved` | inventory to `inventory-events` | payment | `eventId, orderId, customerId, totalAmount, occurredAt` |
| `InventoryReservationFailed` | inventory to `inventory-events` | order | `eventId, orderId, reason, occurredAt` |
| `PaymentCompleted` | payment to `payment-events` | order, inventory | `eventId, orderId, paymentId, totalAmount, occurredAt` |
| `PaymentFailed` | payment to `payment-events` | order, inventory | `eventId, orderId, reason, occurredAt` |

**Headers.** `eventType` routes the message without deserialising it first; `traceparent` carries the W3C trace so one
`traceId` survives the outbox and the broker (NFR-06); payment also sets `eventId`.

**Serialisation.** JSON over `StringSerializer` for key and value. No Java type headers, so no consumer is tied to a
producer's class names.

### Decision 3-1 — each service keeps its own copy of the event records

| | |
|---|---|
| **Decision** | Every service declares the events it produces or consumes as its own records, and consumers ignore unknown fields. order-service goes further and reads incoming events as a `JsonNode`, taking only `eventId`, `orderId` and `reason`. |
| **Options** | (a) a shared `common-events` module on every service's classpath; (b) a copy per service; (c) a schema registry (Avro or Protobuf). |
| **Reason** | (b) keeps the services independently deployable: adding a field to a producer does not force a rebuild of every consumer. The Capstone has no registry infrastructure, so (c) was out of scope. |
| **Trade-off** | The copies can drift silently, and they already have: payment's `InventoryReserved` omits `customerId`, and inventory's `PaymentCompleted` omits `totalAmount`. Both are harmless because those consumers do not use those fields, but nothing in the build would have caught it if they did. |
| **Revisit when** | A renamed or removed field breaks a consumer at runtime, or a fourth service joins the saga. Then introduce (a) with the rule that fields are only ever added. |

**Compatibility rule until then:** fields are **added, never renamed or removed**. A breaking change needs a new event
type, not a new shape for an old one.

---

## 4. Data Model

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

### 5.1 What is synchronous and what is not

| Call | Style | Why |
|---|---|---|
| client to gateway to any service | sync HTTP | the caller is waiting for the answer |
| order to inventory `/check` | sync Feign | the order cannot be accepted without knowing whether stock exists |
| order to product `/{id}` | sync Feign | the order's total must be priced before it is stored |
| order to payment | **async Kafka** | the customer must not wait for a charge, and payment may be down (NFR-01) |
| inventory, payment, notification, analytics reacting to a state change | **async Kafka** | the producer's work is already committed; reacting is not its concern |

The rule we applied: **synchronous when the caller cannot proceed without the answer, asynchronous otherwise.**

### Decision 5-1 — choreography, not an orchestrator

| | |
|---|---|
| **Decision** | The saga is choreographed: each service reacts to the previous service's event. There is no coordinator process. |
| **Options** | (a) choreography over Kafka; (b) an orchestrator in order-service holding saga state in `order_db`. |
| **Reason** | Three participants in a straight line with no branching rules. Choreography needs no component that must stay alive for an order to finish, and "where is order X?" is answered by `GET /api/v1/orders/{id}` plus one Zipkin trace. |
| **Trade-off** | The flow is spread across three services, so understanding it needs this catalogue and the trace. There is no single place that reads out a saga's history. |
| **Revisit when** | A branching rule appears (fraud check, loyalty, partial refund), or a fourth participant joins. Then move to (b), because the branch logic would otherwise be duplicated across consumers. |

### 5.2 The order flow

```
POST /api/v1/orders
  |- sync  order -> inventory /check     (409 OUT_OF_STOCK if no stock, 503 if inventory is down)
  |- sync  order -> product /{id}        (authoritative price)
  '- TX    save order PENDING + outbox row OrderPlaced        <- one transaction
                            |
      order-events ---------'
                |
       inventory: reserve stock, write reservation + outbox InventoryReserved   <- one transaction
                |
      inventory-events
                |
       payment: charge with Idempotency-Key = orderId, outbox PaymentCompleted | PaymentFailed
                |
      payment-events
                |- order:     PENDING -> CONFIRMED  (outbox OrderConfirmed)
                |             PENDING -> CANCELLED  (outbox OrderCancelled)
                '- inventory: consume the reservation, or release it on PaymentFailed
                |
      order-events -> notification (customer notice), analytics (projection)
```

`InventoryReservationFailed` short-circuits the same flow: the order goes straight to `CANCELLED` and payment is never
reached.

### Decision 5-2 — transactional outbox in every producing service

| | |
|---|---|
| **Decision** | order, inventory and payment write the business row and an `outbox_event` row in **one** transaction. A `@Scheduled` poller (500 ms) publishes pending rows, awaits the broker's acknowledgement, then marks them `SENT`. |
| **Options** | (a) publish directly from the service method; (b) transactional outbox with a poller; (c) CDC (Debezium). |
| **Reason** | With (a) a crash between the commit and the send loses the event, and a send before the commit can publish an order that was rolled back. (c) needs infrastructure the Capstone does not have. |
| **Trade-off** | Delivery is **at-least-once**, so every consumer must be idempotent (5-3), and the poll interval adds up to about 500 ms to the saga. |
| **Revisit when** | The outbox backlog grows under k6 load, or the added latency breaks the `POST /orders` P95 target. |

Two details this design lives or dies by, both of which were wrong at first and are now covered by tests:

- **The transaction must actually exist.** `createOrder` calls the persisting method through a `TransactionTemplate`.
  A plain `this.persist(...)` call inside the same bean bypasses the Spring proxy, so `@Transactional` does nothing and
  the order and the outbox row commit separately, which is exactly the dual write the outbox exists to prevent.
- **The remote calls stay outside it.** The stock check and the price lookup happen *before* the transaction opens, so
  no database connection is held open across network I/O.

### Decision 5-3 — idempotent consumers

| | |
|---|---|
| **Decision** | Every consumer inserts the event's `eventId` into its own `processed_event` table in the same transaction as the state change, and re-checks the state before acting. Payment additionally enforces `UNIQUE(order_id)` and an `Idempotency-Key`. |
| **Options** | (a) exactly-once via Kafka transactions; (b) at-least-once plus a dedup table; (c) rely on state checks alone. |
| **Reason** | (b) covers both redelivery and a republish after a crash between `send` and `mark SENT`, and it protects the database, not just the broker. (c) alone cannot tell a redelivery from a legitimate repeat. |
| **Trade-off** | One extra row per event per consumer, and these tables grow without a retention job. |
| **Revisit when** | A `processed_event` table passes roughly 1 M rows. |

State checks are the second guard: an order moves to `CONFIRMED` only from `PENDING`, and stock is released only while
a reservation is `RESERVED`. This is what makes `OrderConfirmed` and `PaymentCompleted`, which both confirm the same
order, safe to receive in either order or twice.

### 5.3 Topics, groups and ordering

| Topic | Produced by | Consumer groups |
|---|---|---|
| `order-events` | order | `inventory-service`, `notification-service`, `order-service-analytics` |
| `inventory-events` | inventory | `payment-service`, `order-service` |
| `payment-events` | payment | `order-service`, `inventory-service` |
| `order-events.retry-N`, `order-events.DLT` | `@RetryableTopic` in notification | `notification-service` |

- **Ordering** is per order, not global: the key is `orderId`, so one order's events share a partition. Different
  orders are processed in parallel, which is what lets the platform scale.
- **Groups** are per service, so adding a consumer (analytics) never steals messages from an existing one.
- **Replay** is possible for any consumer by resetting its group offset; the dedup tables make that safe.

---

## 6. Failure modes

<!-- Owner: B -->

The Brief asks for at least five. The evidence column names the test that proves each one; nothing here is claimed as
proven without it.

| # | What fails | How we notice | What the customer sees | How the platform copes | Evidence |
|---|---|---|---|---|---|
| F1 | Payment service is down | consumer lag on `inventory-events`; the pod is not ready | the order is accepted and stays `PENDING` | events wait in Kafka; when payment returns it charges and the order reaches `CONFIRMED`. Nothing is lost (NFR-01) | chaos step in §8 |
| F2 | Kafka is down when an order is placed | `outbox_event` rows stay `PENDING` | the order is accepted as `PENDING` | the outbox row is committed with the order; the poller drains it when the broker returns | `OutboxPublisherTest` |
| F3 | The same event is delivered twice | a `processed_event` primary-key hit | nothing | the consumer skips it inside the same transaction as its state change (5-3) | `InventoryServiceTest`, `PaymentIdempotencyIT` |
| F4 | Inventory is down during the stock check | circuit-breaker state on `/actuator/health` | `503 STOCK_CHECK_UNAVAILABLE`, fast | retry twice, then the fallback. **No order row is written**, so nothing is left half-done | `OrderSyncIntegrationTest` |
| F5 | There is not enough stock | `InventoryReservationFailed` count | `409 OUT_OF_STOCK` | rejected before any order row exists; payment is never reached | `OrderSyncIntegrationTest` |
| F6 | The charge is declined | `PaymentFailed` events | the order becomes `CANCELLED` and a notice is sent | inventory releases the reservation on `PaymentFailed`; stock returns to `available` | `PaymentSagaKafkaIT`, `DeclinedPaymentIT` |
| F7 | Stock is taken between the check and the reservation | `InventoryReservationFailed` count | the order is created, then becomes `CANCELLED` | the synchronous check is advisory; the atomic conditional `UPDATE` is what actually decides, so the platform never oversells | `StockRepositoryTest` |
| F8 | The publisher crashes after `send` but before marking `SENT` | the same `eventId` seen twice downstream | nothing | at-least-once plus F3 | covered by F3 |
| F9 | A notification send keeps failing | the message lands in `order-events.DLT`; `notifications.dlt` counter; `ALERT` log line | the notice is delayed or missing; **the order itself is unaffected** | 4 attempts with exponential backoff, then the DLT and an alert; replay is manual | `NotificationKafkaIT.shouldRetryThenParkInDlt_whenSendKeepsFailing`, toggle `notification.simulate-failure` |
| F10 | A saga never finishes (payment down for a long time) | orders still `PENDING` past the timeout | the order becomes `CANCELLED` and the customer is told | order-service sweeps `PENDING` orders older than 10 minutes and publishes `OrderCancelled`; inventory releases the stock on it | `PendingOrderSweeperTest`, `OrderServiceTest` |
| F11 | A cancel event is missed, leaving stock held | the NFR-05 query returns a non-zero count | nothing visible; stock would silently leak | the sweeper releases `RESERVED` rows whose order is in `cancelled_order` and older than 30 s | `StockRepositoryTest`, NFR-05 query |
| F12 | Redis is down | cache errors in the logs; the limiter stops counting | product reads are slower but still `200` | reads fall back to the database; the rate limiter **fails open**, availability chosen over protection (D7.4) | documented trade-off |
| F13 | Keycloak is down | JWKS fetch errors at the gateway | existing tokens keep working until they expire; no new logins | the gateway caches the JWKS keys | not tested |

**NFR-05 query** (must return `0`):

```sql
SELECT count(*) FROM reservation r
 WHERE r.status = 'RESERVED'
   AND r.order_id IN (SELECT order_id FROM cancelled_order)
   AND r.created_at < now() - interval '30 seconds';
```

### Decision 6-1 — the sweeper releases stock by saga outcome, never by age

| | |
|---|---|
| **Decision** | Inventory's sweeper releases a reservation only when the order is recorded in `cancelled_order` *and* the row is older than the 30 s grace period. Age alone never releases anything. |
| **Options** | (a) release any `RESERVED` row older than a TTL; (b) release only reservations of orders known to be cancelled; (c) no sweeper, rely purely on the cancel events. |
| **Reason** | (a) **oversells**: an order waiting on a slow or restarting payment service is still in flight, and returning its stock while the saga later confirms it sells the same unit twice. We hit exactly this: a confirmed order whose stock had already been given back. (c) leaves no safety net for the one case events cannot cover, namely `OrderCancelled` arriving *before* `OrderPlaced`, where the release finds no reservation and the later reservation is never cleaned up. |
| **Trade-off** | Inventory now keeps a small `cancelled_order` table, which is a little order state inside the inventory service. It is an append-only record of outcomes, not a copy of the order. |
| **Revisit when** | `cancelled_order` needs retention, or order-service starts publishing a terminal "saga finished" event that inventory could use directly. |

Because the sweeper no longer bounds a stuck saga, order-service owns that timeout instead (F10). The service that
knows the order's state is the one that decides the saga is dead.

### Decision 6-2 — a confirmed order with no held stock is an alert, not a silent success

| | |
|---|---|
| **Decision** | When inventory confirms an order and finds no `RESERVED` row, because the rows were `RELEASED` or never existed, it logs `ALERT oversell` with the order id and the reservation states. |
| **Options** | (a) ignore it, as the old code did; (b) log an alert; (c) throw and let the message retry. |
| **Reason** | This is the signature of the bug in 6-1. Silence is what let it go unnoticed. (c) would retry forever without fixing anything, because the stock is already gone. |
| **Trade-off** | It is a log line, not a repair. Someone has to act on it. |
| **Revisit when** | It fires in practice. Then the alert should become a metric with a Prometheus rule. |

**Known gap (open, not fixed):** the Kafka listeners in order-service and inventory-service catch every exception and
only log it, so a failed saga step is dropped rather than retried. The platform stays consistent because of the
sweepers (F10, F11), but a transient database error silently costs one event. The fix is to let the exception reach
Spring Kafka's error handler so it retries, with a DLT per consumer as notification already has. Left open
deliberately: it changes failure behaviour across two services and needs its own test pass.

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

## 8. Test & Load Plan + Risks

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
