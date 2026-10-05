# Architecture Decision Document — Team

> Complete Architecture Decision Document covering all 8 core sections per `docs/TEAM-GUIDE.md` §1.3 and *Capstone Project Brief* §10.1.

---

## 1. Problem Statement & Business Goals

<!-- Owner: A -->

**Context.** An enterprise e-commerce platform where customers browse a product catalogue, place orders, make payments, and receive notifications, while operations staff manage inventory and view business analytics.

**Core Challenges.**
1. Ensuring high availability and sub-200ms read latency for shoppers browsing products.
2. Managing distributed state and data consistency across independent database-per-service boundaries without distributed two-phase commit (2PC) locks.
3. Guaranteeing that payments and orders are never lost and never charged twice under network failures or broker downtime.
4. Securing service endpoints with centralized token-based identity (Keycloak) and role-based access control.

**Success Measures.**

| Outcome | Measure | Evidence |
|---|---|---|
| Shoppers can always browse | `GET /api/v1/products` p95 < 200 ms, ≥ 50 req/s through the gateway | k6 load report (NFR-02/03) |
| Ordering is fast and safe | `POST /api/v1/orders` p95 < 800 ms at 20 VUs; one charge per order | k6 report; duplicate-payment test (FR-08) |
| No lost orders | Payment down → orders stay PENDING, then complete or are compensated | chaos test (NFR-01) |
| No orphaned stock | 0 RESERVED rows older than 30 s for a CANCELLED order | SQL query in the test plan (NFR-05) |
| Only authorized changes | Missing/invalid token → 401, wrong role → 403 on every protected route | `GatewaySecurityTest`, curl suite (FR-15) |
| Automated reproducibility | `docker compose up` + `verify-l0.sh`; `helm install` / ArgoCD Synced-Healthy | live demo (NFR-08) |

---

## 2. Bounded Context — Bonus B2: Order Analytics

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

### Decision B2-1 — Placement of the analytics module
- **DECISION:** A package `com.ecommerce.order.analytics` inside order-service, with its own tables and consumer group.
- **OPTIONS:** (a) Inside order-service as a separate read side; (b) A new `analytics-service` with its own database; (c) Grafana querying `orders` directly.
- **REASON:** (a) adds no extra deployable, chart, CI job, or database. The data is order data, so order_db is its natural home.
- **TRADE-OFF:** Shares JVM and connection pool with order-service. Because the package and consumer group are decoupled, extracting it later is mechanical.
- **REVISIT WHEN:** Projection lag degrades order API P95 under load, or a second consumer of analytics appears.

### Decision B2-2 — One row per order vs pre-aggregated counters
- **DECISION:** `analytics_order(order_id PK, status, total_amount, placed_at)`. Hourly figures come from `GROUP BY date_trunc('hour', placed_at)`.
- **OPTIONS:** (a) A row per order; (b) Pre-aggregated `order_stats_hourly` counters.
- **REASON:** (a) is correct when events arrive out of order (e.g. `OrderConfirmed` before `OrderPlaced`), and upserts are naturally idempotent.
- **TRADE-OFF:** Read cost grows with orders in window; indexed `placed_at` ensures 24h window queries remain < 50ms.
- **REVISIT WHEN:** The summary query exceeds 200 ms.

### Decision B2-3 — Exactly-once effect on at-least-once topic
- **DECISION:** Deduplication table `analytics_processed_event` updated in the same transaction as the upsert.
- **OPTIONS:** (a) DB dedup table in transaction; (b) Upsert idempotence alone; (c) Kafka transactions.
- **REASON:** (a) prevents double-counting metrics because redelivered events are dropped before incrementing counters.
- **TRADE-OFF:** One extra row per event; retention cleanup deferred.
- **REVISIT WHEN:** `analytics_processed_event` exceeds 1M rows.

---

## 3. API Contract & Domain Events (Frozen)

<!-- Owner: B -->

### 3.1 REST Endpoints

| Service | Method & Path | Access | Request / Response DTO | Error Codes |
|---|---|---|---|---|
| `api-gateway` | `ALL /**` | Public / Role-based | Routes to downstreams | `UNAUTHORIZED` (401), `FORBIDDEN` (403), `RATE_LIMITED` (429) |
| `product-service` | `GET /api/v1/products` | Public | Page params $\to$ `PageResult<ProductResponse>` | `VALIDATION_ERROR` (400) |
| `product-service` | `GET /api/v1/products/{id}` | Public | Path id $\to$ `ProductDetails` | `PRODUCT_NOT_FOUND` (404) |
| `product-service` | `POST /api/v1/products` | `ADMIN` | `ProductRequest` $\to$ `201 Created` | `VALIDATION_ERROR` (400) |
| `inventory-service`| `GET /api/v1/inventory/check` | `SERVICE` (Internal) | `productId, quantity` $\to$ `InventoryCheckResponse` | `OUT_OF_STOCK` (409) |
| `inventory-service`| `PUT /api/v1/inventory/{sku}` | `ADMIN` | `UpdateStockRequest` $\to$ `StockResponse` | `PRODUCT_NOT_FOUND` (404) |
| `order-service` | `POST /api/v1/orders` | `CUSTOMER` | `CreateOrderRequest` $\to$ `201 OrderResponse` | `OUT_OF_STOCK` (409), `SERVICE_UNAVAILABLE` (503) |
| `order-service` | `GET /api/v1/orders/{id}` | `CUSTOMER` | Path id $\to$ `OrderResponse` | `ORDER_NOT_FOUND` (404), `FORBIDDEN` (403) |
| `payment-service` | `POST /api/v1/payments` | `SERVICE`, `ADMIN` | `PaymentRequest` $\to$ `201 PaymentResponse` | `IDEMPOTENCY_KEY_REUSED` (422), `ALREADY_PAID` (200) |
| `payment-service` | `POST /api/v1/payments/{id}/refund` | `ADMIN` | Path id $\to$ `RefundResponse` | `REFUND_NOT_ALLOWED` (409) |
| `order-service` | `GET /api/v1/analytics/summary` | `ADMIN` | `hours` $\to$ `AnalyticsSummaryResponse` | `BAD_REQUEST` (400) |

### 3.2 Kafka Event Schema Contracts

All domain events are serialized as JSON records with Kafka message key = `orderId` and standard headers (`eventType`, `eventId`, `traceparent`).

1. **`OrderPlaced`** (Topic: `order-events`):
   ```json
   {
     "eventId": "UUID",
     "orderId": "UUID",
     "customerId": "string",
     "totalAmount": 119.99,
     "items": [{"productId": "PROD-1", "quantity": 1, "unitPrice": 119.99}],
     "occurredAt": "2026-10-05T12:00:00Z"
   }
   ```
2. **`InventoryReserved`** (Topic: `inventory-events`):
   ```json
   {
     "eventId": "UUID",
     "orderId": "UUID",
     "productId": "PROD-1",
     "quantity": 1,
     "occurredAt": "2026-10-05T12:00:01Z"
   }
   ```
3. **`PaymentCompleted`** (Topic: `payment-events`):
   ```json
   {
     "eventId": "UUID",
     "orderId": "UUID",
     "paymentId": "UUID",
     "amount": 119.99,
     "occurredAt": "2026-10-05T12:00:02Z"
   }
   ```
4. **`PaymentFailed`** (Topic: `payment-events`):
   ```json
   {
     "eventId": "UUID",
     "orderId": "UUID",
     "reason": "PAYMENT_DECLINED",
     "occurredAt": "2026-10-05T12:00:02Z"
   }
   ```
5. **`OrderCancelled`** (Topic: `order-events`):
   ```json
   {
     "eventId": "UUID",
     "orderId": "UUID",
     "customerId": "string",
     "reason": "PAYMENT_FAILED",
     "occurredAt": "2026-10-05T12:00:03Z"
   }
   ```

---

## 4. Data Model

<!-- Owner: C -->

Database-per-service architecture hosted on PostgreSQL 16. Schemas are strictly managed via Flyway (`ddl-auto=validate`).

| Service | Database | Tables (migration) | Owner |
|---|---|---|---|
| `product-service` | `product_db` | `products`, `categories` (`V1__create_catalogue`, `V2__seed_catalogue`) | A |
| `order-service` | `order_db` | `orders`, `order_items`, `outbox_event`, `processed_event` (`V1`–`V3`) | B |
| `order-service` (B2) | `order_db` | `analytics_order`, `analytics_processed_event` (`V50__create_order_analytics`) | C |
| `inventory-service` | `inventory_db` | `stock`, `reservation`, `outbox_event`, `processed_event` (`V1`–`V2`) | B |
| `payment-service` | `payment_db` | `payments`, `idempotency_keys`, `processed_event`, `outbox_event` (`V1`) | C |
| `notification-service` | — | None (State handled in Kafka DLT and memory) | A |

---

## 5. Communication Architecture

<!-- Owner: B -->

### Decision 5.1 — Synchronous Pre-Validation vs Asynchronous Saga
- **DECISION:** Synchronous OpenFeign stock pre-check before order creation; asynchronous Choreographed Saga for order fulfillment and payment.
- **REASON:** Failing fast at the HTTP boundary prevents creating orders that cannot possibly succeed. Asynchronous Saga coordinates multi-service transactions without distributed locks.
- **TRADE-OFF:** Order creation depends synchronously on `inventory-service` availability (mitigated via Resilience4j Circuit Breaker & Retry).

### Decision 5.2 — Transactional Outbox Pattern
- **DECISION:** Never publish directly to Kafka inside `@Transactional`. Persist domain event to `outbox_event` in the DB transaction and publish via `@Scheduled` poller (`FOR UPDATE SKIP LOCKED`).
- **REASON:** Guarantees atomicity between state mutations and event publication without 2PC.
- **TRADE-OFF:** Introduces polling latency (max 500ms) before events reach Kafka.

---

## 6. Failure Modes & Compensation Matrix

<!-- Owner: B -->

| Failure Scenario | Detection Point | Automatic Recovery / Compensation | Final System State |
|---|---|---|---|
| **Inventory Out of Stock** | Feign stock pre-check in `order-service` | Immediate `409 Conflict` returned to client | No order created; stock untouched |
| **Inventory Service Down** | Resilience4j Circuit Breaker in `order-service` | OpenFeign fallback returns `503 Service Unavailable` | No order created; client retries later |
| **Payment Declined / Failed** | `payment-service` simulation or card decline | `PaymentFailed` emitted $\to$ `order-service` cancels order (`CANCELLED`); `inventory-service` releases stock | Order `CANCELLED`, Stock available, Client notified |
| **Abandoned / Stalled Order** | `ReservationSweeper` in `inventory-service` | Scheduled query identifies `RESERVED` rows older than 30s TTL | Stock released; reservation marked `CANCELLED` |
| **Duplicate Kafka Event** | Consumer `processed_event` unique constraint | Catch `DataIntegrityViolationException` and acknowledge message | Business action executed exactly once |
| **Poison Message in Kafka** | Jackson parsing error in consumer | Retry with exponential backoff $\to$ park in `<topic>.DLT` | Alert logged, normal processing uninterrupted |

---

## 7. Security & Deployment

<!-- Owner: A -->

### 7.1 Roles per Endpoint (Enforced at Gateway & Services)

| Endpoint | Access | Enforced by |
|---|---|---|
| `GET /api/v1/products/**` | Public (Rate Limited) | Gateway `permitAll` + `RequestRateLimiter`; Product `@PermitAll` |
| `POST/PUT/DELETE /api/v1/products/**` | `ADMIN` | Gateway `hasRole(ADMIN)`; Product `@PreAuthorize("hasRole('ADMIN')")` |
| `/api/v1/orders/**` | `CUSTOMER` (Own orders only) | Gateway `hasRole(CUSTOMER)`; Order `customerId` token claim match |
| `GET /api/v1/inventory/check` | `SERVICE` (Internal) | Gateway `denyAll`; Inventory service role verification |
| `/api/v1/inventory/**`, `/api/v1/payments/**`, `/api/v1/analytics/**` | `ADMIN` | Gateway `hasRole(ADMIN)`; Service RBAC annotations |
| `/actuator/**` | Internal / Scraped | Internal cluster network only |

### 7.2 Deployment & Hardening
- Multi-stage Docker builds (`eclipse-temurin:21-jre-alpine`) running as unprivileged user `10001`.
- Kubernetes manifests in `deployment/kubernetes/` deployed via Helm (`deployment/helm/microservice`) with read-only root filesystems and non-root security contexts.
- GitOps delivery managed via ArgoCD Application and ApplicationSets tracking `env/dev`.

---

## 8. Test & Load Plan + Risks

<!-- Owner: C -->

### Test Levels

| Level | Tool | Scope |
|---|---|---|
| Unit | JUnit 5 + Mockito | Domain state transitions, Outbox publishers, Simulator logic |
| Web slice | `@WebMvcTest` + `spring-security-test` | Controllers, RFC 7807 problem details, role security matrix |
| Integration | Testcontainers PostgreSQL 16 & Kafka | Idempotency constraints, Flyway migrations, end-to-end Kafka listeners |
| Performance | k6 | Smoke (1 VU), Load (20 VUs, P95 < 200ms/800ms), Stress (150 VUs) |

### Top Project Risks & Mitigations
1. **Memory Pressure on Local Docker (< 6GB):** Enforce `mem_limit` per container and SerialGC with `MaxRAMPercentage=75`.
2. **Distributed Tracing Loss over Async Boundaries:** Enforce W3C `traceparent` preservation in outbox tables and Kafka record headers.
3. **Double Charging / Over-Reservation:** Enforce DB composite primary keys on `processed_event` tables and atomic database decrements.
