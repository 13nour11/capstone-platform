# capstone-platform — Enterprise E-Commerce Platform

Spring Boot 3.5 · Spring Cloud 2025.0 · Java 21 · Maven multi-module. Customers browse a catalogue, place orders
and are notified; admins manage the catalogue and stock.

| Module | Port | Owner |
|---|---|---|
| `platform/config-server` | 8888 | B |
| `platform/eureka-server` | 8761 | B |
| `platform/api-gateway` | 8080 | A |
| `services/product-service` | 8081 | A |
| `services/order-service` | 8082 | B (analytics: C) |
| `services/payment-service` | 8083 | C |
| `services/inventory-service` | 8084 | B |
| `services/notification-service` | 8085 | A |
| `services/review-service` (Bonus B1) | 8086 | Team |

Infrastructure (`deployment/docker/docker-compose.yml`): PostgreSQL 5432 · Kafka 9092 (+ Zookeeper 2181) ·
Redis 6379 · Keycloak 8180 · Zipkin 9411 · Prometheus 9090 · Grafana 3000.

Each section below has one owner (`docs/TEAM-GUIDE.md` §4.2). Write only under your own heading.

## Run locally

<!-- Owner: B -->

Prerequisites: Docker Desktop with at least 8 GB RAM, JDK 21, Maven 3.9.

```bash
cp .env.example .env            # then replace every change-me value
docker compose --env-file .env -f deployment/docker/docker-compose.yml up -d --build
./scripts/verify-l0.sh          # L0 Definition of Done
mvn -B verify                   # unit + Testcontainers tests + JaCoCo gate
```

Run a service from its module directory (it reads secrets from the repo-root `.env` and its settings from
config-server), e.g. `cd services/product-service && mvn spring-boot:run`.

Run platform and member-B services one by one (each needs config-server first):

### 1. Start Config & Eureka Servers (Platform)
```bash
# Config Server
mvn spring-boot:run -f platform/config-server/pom.xml

# Eureka Server
mvn spring-boot:run -f platform/eureka-server/pom.xml
```

### 2. Start Inventory Service
```bash
# Runs on port 8084
mvn spring-boot:run -f services/inventory-service/pom.xml
```

### 3. Start Order Service
```bash
# Runs on port 8082
mvn spring-boot:run -f services/order-service/pom.xml
```

### 4. Running Verification Test Suites
```bash
# Test Inventory Service
mvn test -f services/inventory-service/pom.xml

# Test Order Service
mvn test -f services/order-service/pom.xml
```

## Security & curl checks

<!-- Owner: A -->

**Model.** Keycloak realm `ecommerce-platform` issues JWTs (roles `ADMIN`, `CUSTOMER`, `SERVICE`). The gateway is
an OAuth2 Resource Server: it validates every token, applies the role rule per path, removes any client-sent
`X-User-*` header and adds `X-User-Id` / `X-User-Roles` (and `X-Tenant-Id`, Bonus B3) from the token.
product, inventory, payment and review-service validate the JWT again; order-service takes the customer from
`X-User-Id` (ADD D7.1). Errors follow one RFC 7807 shape with a stable `code`
(`UNAUTHORIZED`, `FORBIDDEN`, `RATE_LIMITED`, `VALIDATION_ERROR`, `PRODUCT_NOT_FOUND`, …).

| Path | Rule |
|---|---|
| `GET /api/v1/products/**` | public, rate limited per user or client IP (FR-13) |
| `POST/PUT/DELETE /api/v1/products/**` | `ADMIN` |
| `/api/v1/orders/**` | `CUSTOMER` |
| `/api/v1/inventory/check` | never routed (internal, order-service → inventory-service only) |
| `/api/v1/inventory/**`, `/api/v1/payments/**`, `/api/v1/analytics/**`, `/api/v1/alerts/**` | `ADMIN` |
| `GET /api/v1/products/{id}/reviews` · `POST` (B1) | public · `CUSTOMER` |
| anything else | denied |

Test users `admin`, `customer1` (tenant `tenant-a`) and `admin-b`, `customer2` (tenant `tenant-b`) share the password
`KC_TEST_USER_PASSWORD` from `.env`.
The confidential client `order-service` (service role `SERVICE`) uses the client-credentials grant (FR-14).

The commands below use bash (Git Bash on Windows) from the repo root, with gateway, product-service and config-server running.

```bash
set -a; . ./.env; set +a
KC=http://localhost:8180/realms/ecommerce-platform/protocol/openid-connect/token
token() { curl -s "$KC" -d grant_type=password -d client_id=api-gateway -d username="$1" \
            -d password="$KC_TEST_USER_PASSWORD" | sed -E 's/.*"access_token":"([^"]+)".*/\1/'; }
ADMIN=$(token admin); CUSTOMER=$(token customer1)

# FR-01 public, paginated (max size 50, sort by id|name|price) -> 200
curl -i "http://localhost:8080/api/v1/products?page=0&size=5&sort=price,desc"
# FR-03 detail with category name -> 200
curl -i http://localhost:8080/api/v1/products/1
# FR-04 write without a token -> 401 {"code":"UNAUTHORIZED"}
curl -i -X POST http://localhost:8080/api/v1/products -H 'Content-Type: application/json' \
     -d '{"name":"Desk Mat","price":19.90,"categoryId":3}'
# FR-02 CUSTOMER write -> 403 {"code":"FORBIDDEN"}
curl -i -X POST http://localhost:8080/api/v1/products -H "Authorization: Bearer $CUSTOMER" \
     -H 'Content-Type: application/json' -d '{"name":"Desk Mat","price":19.90,"categoryId":3}'
# FR-02 ADMIN create -> 201 + Location; update -> 200; delete -> 204 (each write evicts the cache, FR-15)
ID=$(curl -s -X POST http://localhost:8080/api/v1/products -H "Authorization: Bearer $ADMIN" \
     -H 'Content-Type: application/json' -d '{"name":"Desk Mat","price":19.90,"categoryId":3}' \
     | sed -E 's/.*"id":([0-9]+).*/\1/')
curl -i -X PUT http://localhost:8080/api/v1/products/$ID -H "Authorization: Bearer $ADMIN" \
     -H 'Content-Type: application/json' -d '{"name":"Desk Mat XL","price":24.90,"categoryId":3}'
curl -i -X DELETE http://localhost:8080/api/v1/products/$ID -H "Authorization: Bearer $ADMIN"
# Validation -> 400 {"code":"VALIDATION_ERROR","errors":[...]}
curl -i "http://localhost:8080/api/v1/products?size=51"
# Internal stock check is not exposed -> 403
curl -i "http://localhost:8080/api/v1/inventory/check?productId=1&quantity=1" -H "Authorization: Bearer $ADMIN"
# FR-13 rate limit (default 20 req/s, burst 40): 100 parallel requests from one curl -> ~40-50 x 200, the rest 429
# {"code":"RATE_LIMITED"} + Retry-After (a shell loop is too slow on Windows to empty the bucket)
curl -s -Z --parallel-max 100 -w '\n%{http_code}\n' $(printf 'http://localhost:8080/api/v1/products/1 %.0s' $(seq 100)) \
  | grep -E '^[0-9]{3}$' | sort | uniq -c
# FR-15 cache: the item and page keys appear after a read and disappear after a write
docker exec capstone-redis-1 redis-cli --scan --pattern 'product*'
# FR-14 client credentials for service-to-service calls -> access_token with realm role SERVICE
curl -s "$KC" -d grant_type=client_credentials -d client_id=order-service -d client_secret="$ORDER_SERVICE_CLIENT_SECRET"
```

Tests: `GatewaySecurityTest` (role matrix, 401/403 JSON, header spoofing), `RateLimitIT` (Redis, 429),
`RoutesConfigTest` (real `config-repo/api-gateway.yml`), `ProductControllerTest` (401/403/201/400/404),
`ProductRepositoryIT` (PostgreSQL + Flyway + projection), `ProductCacheIT` (Redis eviction).

## Orders & Saga

<!-- Owner: B -->

### Choreography-based Saga Architecture

The platform uses an asynchronous choreography-based Saga to coordinate transactions across distributed services (`order-service`, `inventory-service`, and `payment-service`) without distributed two-phase commit (2PC) locks.

```
[Client] 
   │ POST /api/v1/orders
   ▼
[order-service] ──(HTTP sync check)──► [inventory-service]
   │ (Persists Order PENDING + OutboxEvent)
   │
   ▼ (Async Outbox Poller)
[Kafka: order-events] (OrderPlaced)
   │
   ├──► [inventory-service]
   │       ├── Reserves stock atomically & saves Reservation
   │       └── Persists OutboxEvent (InventoryReserved or InventoryReservationFailed)
   │              │
   │              ▼ (Async Outbox Poller)
   │        [Kafka: inventory-events] (InventoryReserved)
   │              │
   │              └──► [payment-service]
   │                      ├── Processes payment
   │                      └── Emits PaymentCompleted or PaymentFailed
   │                             │
   ▼                             ▼
[order-service] ◄── [Kafka: payment-events]
   ├── On PaymentCompleted: Order -> CONFIRMED, emits OrderConfirmed
   └── On PaymentFailed: Order -> CANCELLED, emits OrderCancelled
          │
          └──► [inventory-service] receives PaymentFailed / OrderCancelled:
                  Releases reserved stock back to available pool
```

### Architectural Invariants & Patterns

1. **Transactional Outbox Pattern**:
   - Zero `kafkaTemplate.send()` calls inside database `@Transactional` blocks.
   - Business entities and `outbox_event` records are committed in the **same local database transaction**.
   - An asynchronous `@Scheduled` poller queries pending outbox events using `SELECT ... FOR UPDATE SKIP LOCKED` and publishes records to Kafka with `key = orderId`.
   - The event is marked `SENT` only after the broker acknowledged it; on a failure the batch stops and is retried on the next poll (at-least-once).

2. **Distributed Tracing Continuity (NFR-06)**:
   - The active W3C `traceparent` header is captured and persisted in the `outbox_event.traceparent` column.
   - The publisher continues that trace when it sends the row; the KafkaTemplate observation then writes the `traceparent` header, so one traceId spans the HTTP request and every Kafka hop.

3. **Idempotent Consumer Processing (NFR-10)**:
   - Each consumer service maintains a `processed_event(event_id, consumer)` table.
   - Every incoming event checks for duplicate processing within the consumer transaction. Duplicate events are silently discarded, guaranteeing exactly-once semantics at the business layer.

4. **NFR-05 Reservation Sweeper**:
   - `inventory-service` releases a cancelled order's stock as soon as `PaymentFailed` or `OrderCancelled` arrives, records the order in `cancelled_order`, and publishes `InventoryReleased`.
   - A scheduled sweeper (`ReservationSweeper`, every 10 seconds) releases any reservation still `RESERVED` for a cancelled order; a late `OrderPlaced` for a cancelled order reserves nothing.
   - Reservations of orders that are only waiting (for example while payment-service is down, NFR-01) are never released, so a slow payment cannot lose stock.
   - One reservation row per order line, so multi-product orders release and consume every product.

5. **Failure Compensation Paths**:
   - **Out of Stock**: Synchronous check returns `409 Conflict` (`OUT_OF_STOCK`); order is never saved.
   - **Payment Failure**: `payment-service` emits `PaymentFailed`. `order-service` cancels order (`CANCELLED`); `inventory-service` compensates by releasing the reserved stock.
   - **Inventory Reservation Failure**: If concurrent orders deplete stock before async reservation, `inventory-service` emits `InventoryReservationFailed`. `order-service` marks the order `CANCELLED`.

6. **Resilience of the stock check (FR-06)**: Feign through Eureka, wrapped in Retry, CircuitBreaker, TimeLimiter (2 s) and Bulkhead; a failed check answers `503 STOCK_CHECK_UNAVAILABLE` and nothing is saved. The call carries order-service's own client-credentials token (FR-14).

```bash
# Happy path: 201 PENDING, then CONFIRMED a moment later (FR-05, FR-09); the order API takes the catalogue price
ORDER=$(curl -s -X POST http://localhost:8080/api/v1/orders -H "Authorization: Bearer $CUSTOMER" \
     -H 'Content-Type: application/json' -d '{"items":[{"productId":1,"quantity":1,"unitPrice":24.99}]}')
echo "$ORDER"; OID=$(echo "$ORDER" | sed -E 's/.*"orderId":"([^"]+)".*/\1/')
sleep 3; curl -s http://localhost:8080/api/v1/orders/$OID -H "Authorization: Bearer $CUSTOMER"
# Out of stock (product 4 has 0 units) -> 409 OUT_OF_STOCK, no order, no payment (FR-06)
curl -i -X POST http://localhost:8080/api/v1/orders -H "Authorization: Bearer $CUSTOMER" \
     -H 'Content-Type: application/json' -d '{"items":[{"productId":4,"quantity":1,"unitPrice":279.00}]}'
# Compensation: set PAYMENT_FAILURE_RATE=1.0 in .env, recreate payment-service, order again -> CANCELLED, stock restored
# NFR-05 evidence (must print 0 rows): see docs/adr/ADD-TEAM.md §4
```

## Payment

<!-- Owner: C -->

payment-service (port 8083) charges each order **exactly once** and reports the result to the Saga.

| Endpoint | Access | Behaviour |
|---|---|---|
| `POST /api/v1/payments` (header `Idempotency-Key` required) | SERVICE, ADMIN | `201` charges the order. A retry with the same key replays the stored response with `Idempotent-Replayed: true`. The same key with a different body returns `422 IDEMPOTENCY_KEY_REUSED`. A new key for an already-paid order returns `200` with the existing payment. |
| `POST /api/v1/payments/{id}/refund` | ADMIN | Refunds a completed payment once; a repeat returns the same result. A declined payment returns `409 REFUND_NOT_ALLOWED`. |

**Saga hop.** Payment consumes `InventoryReserved` from `inventory-events` (group `payment-service`). In one transaction it writes:
- the `processed_event` row,
- the payment,
- an outbox row with `PaymentCompleted` or `PaymentFailed`.

The outbox poller publishes that row to `payment-events` with headers `eventType`, `eventId` and `traceparent`. A record that cannot be read goes to `inventory-events.DLT`.

**Failure switch.** `payment.simulation.failure-rate` in `config-repo/payment-service.yml` (`PAYMENT_FAILURE_RATE` in the repo-root `.env`):
- `0.0`: every payment succeeds.
- `1.0`: every payment fails, which drives the compensation path.

**Outbox reference design.** order-service and inventory-service reuse this design:

| Piece | Where |
|---|---|
| Table | `outbox_event` in `V1__create_payment_tables.sql` |
| Writer (joins the business transaction) | `OutboxWriter` |
| Poller (`FOR UPDATE SKIP LOCKED`, marks a row sent only after the broker acknowledged it, stops the batch on the first failure) | `OutboxPublisher` |
| Consumer contract | Route on the `eventType` header; deduplicate on `eventId` |

**Run and test.**

```bash
cd services/payment-service
mvn verify                                     # unit + Testcontainers + embedded Kafka tests, JaCoCo report
mvn spring-boot:run -Dspring-boot.run.arguments=--spring.config.additional-location=file:../../config-repo/payment-service.yml
```

`spring-boot:run` reads `PAYMENT_DB_PASSWORD` from the repo-root `.env`. The `additional-location` argument is only needed while config-server is not running.

## Kubernetes

<!-- Owner: C -->

The kind cluster and infrastructure manifests live in `deployment/kubernetes/`. The full guide is [deployment/kubernetes/README.md](deployment/kubernetes/README.md).

```bash
kind create cluster --config deployment/kubernetes/kind-config.yaml
kubectl apply -f deployment/kubernetes/infra/namespace.yaml
scripts/create-k8s-secrets.sh                 # Secrets + postgres-init + dashboards from the repo-root .env (never committed)
kubectl apply -f deployment/kubernetes/infra/
kubectl -n ecommerce get pods -w
# Services: through ArgoCD (Helm & GitOps below) or one Helm release each, e.g.
helm upgrade --install product-service deployment/helm/microservice -f deployment/helm/values/product-service.yaml -n ecommerce
```

| UI | URL (kind) |
|---|---|
| Keycloak | http://localhost:8180 |
| Zipkin | http://localhost:9411 |
| Prometheus | http://localhost:9090 |
| Grafana | http://localhost:3000 |
| API Gateway (Helm NodePort) | http://localhost:30080 |

## Helm & GitOps

<!-- Owner: A -->

One chart, `deployment/helm/microservice`, deploys every service; `deployment/helm/values/<service>.yaml` holds the
differences. Each pod runs as non-root (uid 10001) with a read-only root filesystem, dropped capabilities,
startup/liveness/readiness probes on `/actuator/health/*`, its own ServiceAccount without an API token, and
its secrets from `<service>-secrets` (never in Git). Details: [deployment/helm/README.md](deployment/helm/README.md).

ArgoCD deploys `infra` (raw manifests) and one Application per service (ApplicationSet), automated with
`prune` + `selfHeal`, tracking the `env/dev` branch: on every push to `main`, CI merges `main` into `env/dev` and
commits the new `:sha` image tags there. Install and the drift demo:
[deployment/argocd/README.md](deployment/argocd/README.md).

## Observability

<!-- Owner: B -->

Distributed tracing is configured across all services via Micrometer Tracing with Brave and Zipkin exporter (`http://localhost:9411/api/v2/spans`). All Kafka events propagate W3C `traceparent` headers to correlate spans across HTTP requests and Kafka message processing.

- **Logs:** JSON (Elastic Common Schema) on the console, with `traceId` and `spanId` on every line (`logging.structured.format.console: ecs`).
- **Trace of one order:** place an order, then open Zipkin (http://localhost:9411) and search by `serviceName=api-gateway`; the trace spans gateway → order → inventory → payment → order → notification.
- **Metrics:** Prometheus (http://localhost:9090) scrapes every service; Grafana (http://localhost:3000) provisions *Platform Overview* and *Order Analytics*.

## Load tests

<!-- Owner: A -->

[k6](https://k6.io) scripts live in `k6/`; tokens come from Keycloak at run time (`lib/auth.js`, renewed before expiry).

| Script | Shape | Pass criteria |
|---|---|---|
| `smoke-test.js` | 1 VU, 1 min | no errors |
| `load-test.js` | 60 catalogue req/s + 20 VUs ordering, 5 min | `GET /products` p95 < 200 ms, `POST /orders` p95 < 800 ms, ≥ 50 req/s, errors < 1 % |
| `stress-test.js` | ramp to 150 VUs, 11 min | records the breaking point (no abort) |

```bash
# The anonymous browse traffic comes from one IP: raise the gateway limit for the run, e.g. in .env
#   GATEWAY_RATE_LIMIT_REPLENISH_RATE=1000  GATEWAY_RATE_LIMIT_BURST_CAPACITY=2000   (then restart the gateway)
k6 run -e K6_PASSWORD="$KC_TEST_USER_PASSWORD" k6/smoke-test.js
k6 run -e K6_PASSWORD="$KC_TEST_USER_PASSWORD" --summary-export k6/results/load.json k6/load-test.js
k6 run -e K6_PASSWORD="$KC_TEST_USER_PASSWORD" --summary-export k6/results/stress.json k6/stress-test.js
# Ordering runs log in as admin once in setup() to read prices and top up the stock of products 1-3
# (K6_ADMIN_PASSWORD, default K6_PASSWORD). The customer logs in once in setup() too; VUs renew that token with
# the refresh-token grant (the realm's brute-force protection locks a user hit by many parallel password logins).
# Add -e SKIP_ORDERS=true to measure the catalogue only.
# Stream results to Prometheus/Grafana: K6_PROMETHEUS_RW_SERVER_URL=http://localhost:9090/api/v1/write k6 run -o experimental-prometheus-rw ...
```

Results, the bottleneck found and the before/after fix: [docs/PERFORMANCE-REPORT.md](docs/PERFORMANCE-REPORT.md).

## Bonus B2 — Order Analytics

<!-- Owner: C -->

An ADMIN view of order volume, revenue and Saga failures, built from `order-events` with no new infrastructure.

- **Read model:** `analytics_order` in `order_db`, written by consumer group `order-service-analytics`. It is idempotent per `eventId` (`analytics_processed_event`) and safe when `OrderConfirmed` arrives before `OrderPlaced`.
- **API:** `GET /api/v1/analytics/summary?hours=24` (ADMIN, 1–168 hours). It returns orders by status, revenue from confirmed orders, the cancelled ratio and an hourly breakdown.
- **Metrics:** `analytics_orders_total{status="PLACED|CONFIRMED|CANCELLED"}` and `analytics_revenue_total`, incremented only after commit.
- **Dashboard:** Grafana → *Capstone / Order Analytics* (http://localhost:3000). Panels: orders per minute by status, revenue, Saga failure rate.

```bash
curl -H "Authorization: Bearer $ADMIN_TOKEN" "http://localhost:8080/api/v1/analytics/summary?hours=24"
```

## Bonus B1 — Product Reviews & Ratings

<!-- Owner: Team -->

review-service (8086, own `review_db`) stores one review per customer and product; product-service shows the average
and count, updated through `ReviewSubmitted` events (outbox → `review-events`). Duplicates cannot skew the average:
the rating table is keyed by review id.

```bash
curl -i -X POST http://localhost:8080/api/v1/products/1/reviews -H "Authorization: Bearer $CUSTOMER" \
     -H 'Content-Type: application/json' -d '{"rating":5,"comment":"Great mouse"}'      # 201; a second one -> 409
curl -s "http://localhost:8080/api/v1/products/1/reviews?page=0&size=10"                 # public, paginated
sleep 2; curl -s http://localhost:8080/api/v1/products/1                                  # averageRating, ratingCount
```

## Bonus B3 — Multi-Tenant Gateway

<!-- Owner: Team -->

Two shops share the platform. The gateway takes the tenant from the token claim `tenant_id` (Keycloak user
attribute) and forwards it as `X-Tenant-Id`; anonymous catalogue reads may pick `tenant-a` (default) or `tenant-b`
with that header. product-service filters every query and cache key by tenant, and rate-limit buckets are per tenant.

```bash
ADMIN_B=$(token admin-b)
curl -s http://localhost:8080/api/v1/products -H 'X-Tenant-Id: tenant-b'                 # tenant B's catalogue
curl -i -X DELETE http://localhost:8080/api/v1/products/1 -H "Authorization: Bearer $ADMIN_B"   # 404: tenant A's product
curl -i http://localhost:8080/api/v1/products -H "Authorization: Bearer $ADMIN_B" -H 'X-Tenant-Id: tenant-a'  # 403 TENANT_MISMATCH
```

## Bonus B4 — Real-Time Inventory Alerts

<!-- Owner: Team -->

When a product's available stock drops below `inventory.low-stock.threshold` (default 5), inventory-service
publishes one `LowStock` event (deduplicated per product until the stock recovers); notification-service pushes it
to every connected admin over Server-Sent Events, through the gateway.

```bash
curl -N http://localhost:8080/api/v1/alerts/stream -H "Authorization: Bearer $ADMIN"     # keep open: the admin client
# in a second terminal: drop product 2 below the threshold -> a "low-stock" event appears in the stream
curl -s -X PUT http://localhost:8080/api/v1/inventory/2 -H "Authorization: Bearer $ADMIN" \
     -H 'Content-Type: application/json' -d '{"availableQuantity":3}'
curl -s http://localhost:8080/api/v1/inventory/low-stock -H "Authorization: Bearer $ADMIN"
```
