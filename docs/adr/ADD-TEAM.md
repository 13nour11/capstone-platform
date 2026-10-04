# Architecture Decision Document — Team

> Sections are owned per `docs/TEAM-GUIDE.md` §1.3. Each decision follows
> DECISION · OPTIONS CONSIDERED · REASON · TRADE-OFF · WHAT WOULD MAKE US REVISIT.

<!-- §1 Problem statement — owner A -->

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

<!-- §3 API contract + events — owner B -->

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

<!-- §5 Communication · §6 Failure modes — owner B -->
<!-- §7 Security & deployment — owner A -->

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
