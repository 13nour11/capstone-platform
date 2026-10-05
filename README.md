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
`X-User-*` header and adds `X-User-Id` / `X-User-Roles` from the token. Services validate the JWT again
(defence in depth). Errors follow one RFC 7807 shape with a stable `code`
(`UNAUTHORIZED`, `FORBIDDEN`, `RATE_LIMITED`, `VALIDATION_ERROR`, `PRODUCT_NOT_FOUND`, …).

| Path | Rule |
|---|---|
| `GET /api/v1/products/**` | public, rate limited per user or client IP (FR-13) |
| `POST/PUT/DELETE /api/v1/products/**` | `ADMIN` |
| `/api/v1/orders/**` | `CUSTOMER` |
| `/api/v1/inventory/check` | never routed (internal, order-service → inventory-service only) |
| `/api/v1/inventory/**`, `/api/v1/payments/**`, `/api/v1/analytics/**` | `ADMIN` |
| anything else | denied |

Test users `admin`, `customer1`, `customer2` share the password `KC_TEST_USER_PASSWORD` from `.env`.
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
curl -i -X POST http://localhost:8080/api/v1/products -H "Authorization: Bearer $ADMIN" \
     -H 'Content-Type: application/json' -d '{"name":"Desk Mat","price":19.90,"categoryId":3}'
curl -i -X PUT http://localhost:8080/api/v1/products/11 -H "Authorization: Bearer $ADMIN" \
     -H 'Content-Type: application/json' -d '{"name":"Desk Mat XL","price":24.90,"categoryId":3}'
curl -i -X DELETE http://localhost:8080/api/v1/products/11 -H "Authorization: Bearer $ADMIN"
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
   - On successful publish, the event status is marked as `SENT`.

2. **Distributed Tracing Continuity (NFR-06)**:
   - The active W3C `traceparent` header is captured and persisted in the `outbox_event.traceparent` column.
   - When the outbox publisher dispatches the Kafka `ProducerRecord`, the `traceparent` is injected as a Kafka record header, ensuring zero trace context loss between asynchronous threads and services.

3. **Idempotent Consumer Processing (NFR-10)**:
   - Each consumer service maintains a `processed_event(event_id, consumer)` table.
   - Every incoming event checks for duplicate processing within the consumer transaction. Duplicate events are silently discarded, guaranteeing exactly-once semantics at the business layer.

4. **NFR-05 Reservation Timeout Sweeper**:
   - `inventory-service` executes a scheduled sweeper (`ReservationSweeper`) every 10 seconds.
   - Any reservation in `RESERVED` status older than 30 seconds (`inventory.reservation.ttl-seconds: 30`) is automatically released and refunded to available stock, preventing stock leakage from abandoned or unconfirmed orders.

5. **Failure Compensation Paths**:
   - **Out of Stock**: Synchronous check returns `409 Conflict` (`OUT_OF_STOCK`); order is never saved.
   - **Payment Failure**: `payment-service` emits `PaymentFailed`. `order-service` cancels order (`CANCELLED`); `inventory-service` compensates by releasing the reserved stock.
   - **Inventory Reservation Failure**: If concurrent orders deplete stock before async reservation, `inventory-service` emits `InventoryReservationFailed`. `order-service` marks the order `CANCELLED`.

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

**Failure switch.** `payment.simulation.failure-rate` in `config-repo/payment-service.yml` (`PAYMENT_FAILURE_RATE` in `deployment/docker/.env`):
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

`spring-boot:run` reads the password from the `SPRING_DATASOURCE_PASSWORD` environment variable. The `additional-location` argument is only needed while config-server is not running.

## Kubernetes

<!-- Owner: C -->

The kind cluster and infrastructure manifests live in `deployment/kubernetes/`. The full guide is [deployment/kubernetes/README.md](deployment/kubernetes/README.md).

```bash
kind create cluster --config deployment/kubernetes/kind-config.yaml
kubectl apply -f deployment/kubernetes/infra/namespace.yaml
scripts/create-k8s-secrets.sh                 # Secrets from deployment/docker/.env (never committed)
kubectl apply -f deployment/kubernetes/infra/
kubectl -n ecommerce get pods -w
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
`prune` + `selfHeal`, tracking the `env/dev` branch that CI updates. Install and the drift demo:
[deployment/argocd/README.md](deployment/argocd/README.md).

## Observability

<!-- Owner: B -->

Distributed tracing is configured across all services via Micrometer Tracing with Brave and Zipkin exporter (`http://localhost:9411/api/v2/spans`). All Kafka events propagate W3C `traceparent` headers to correlate spans across HTTP requests and Kafka message processing.

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
# Before the Saga exists (L1/L2), add -e SKIP_ORDERS=true to measure the catalogue only.
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
