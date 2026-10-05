# Capstone Platform Microservices

An enterprise e-commerce platform built with Spring Boot 3, Spring Cloud, Kafka, and PostgreSQL, demonstrating Choreography-based Saga, Transactional Outbox, and Distributed Tracing.

---

## Run locally
*(Maintained by Member B)*

### Prerequisites

| Tool | Version | Note |
|---|---|---|
| Java | 21 | |
| Maven | 3.9+ | |
| Docker + Compose v2 | Docker Desktop with **≥ 6 GB RAM** | Keycloak needs ~60 s on first start |

### 1. Start the whole platform (NFR-08)

```bash
cp deployment/docker/.env.example deployment/docker/.env     # then replace every change-me value
docker compose -f deployment/docker/docker-compose.yml up -d --build
scripts/verify-l0.sh                                           # L0 check, see below
```

`scripts/verify-l0.sh` is the L0 Definition of Done (Brief §6). It waits for, and checks:

1. every infrastructure container is healthy: postgres, zookeeper, kafka, redis, keycloak, zipkin, prometheus, grafana;
2. config-server is `UP` and serves `application.yml` plus the file of every service from `config-repo/`;
3. eureka-server is `UP` and every platform service container that is running is registered as `UP`.

It ends with `L0 GREEN` (exit 0) or `L0 RED` with the failing check and the command to investigate it.
Options: `WAIT_SECONDS=300` (slow machine), `SKIP_INFRA=1` (services started from the IDE),
`EXPECTED_APPS="order-service inventory-service"` (require specific registrations).

### 2. Run one service from the IDE or Maven

Start the infrastructure, config-server and eureka-server with Compose, stop the container of the service you work
on, then run it locally; it reads its configuration from config-server on `localhost:8888`:

```bash
docker compose -f deployment/docker/docker-compose.yml stop order-service
export SPRING_DATASOURCE_PASSWORD=<POSTGRES_PASSWORD from deployment/docker/.env>
mvn -pl services/order-service spring-boot:run        # :8082 (inventory-service: :8084)
```

### 3. Tests

```bash
mvn verify                                            # all modules, JaCoCo gate on the service layer (NFR-07)
mvn -pl services/order-service -am verify             # one module
```

`*IT` / `*RepositoryTest` classes that use **Testcontainers** need a running Docker. The Kafka ITs
(`OrderSagaDeadLetterIT`, `InventorySagaDeadLetterIT`) use an embedded broker and run anywhere.

### 4. Service-to-service authentication (FR-14)

order-service calls inventory's `/check` with its own client-credentials token (Keycloak client `order-service`,
realm role `SERVICE`), and inventory then rejects the call without it. Both sides ship **switched off**, because the
order-service container must first receive the client secret. Turn them on together:

| Service | Variable | Value |
|---|---|---|
| order-service | `ORDER_SERVICE_AUTH_ENABLED` | `true` |
| order-service | `ORDER_SERVICE_CLIENT_SECRET` | the same value Keycloak imports (`.env`) |
| order-service | `KEYCLOAK_TOKEN_URI` | `http://keycloak:8180/realms/ecommerce-platform/protocol/openid-connect/token` (Compose) |
| inventory-service | `INVENTORY_REQUIRE_SERVICE_TOKEN` | `true` |

In Kubernetes the secret goes into `order-service-secrets` (`scripts/create-k8s-secrets.sh`). Evidence:
`ServiceTokenIntegrationTest`, `InventoryServiceTokenSecurityTest`.

---

## Security & curl checks
*(Maintained by Member A)*

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

---

## Orders & Saga
*(Maintained by Member B)*

The sequence (happy path and the payment-declined compensation) is drawn in
[`docs/architecture/02-order-sequence.svg`](docs/architecture/02-order-sequence.svg); the reasoning is in ADD §5
and the failure modes in ADD §6.

### How an order flows (choreography, ADD Decision 5-1)

| Step | Who | What happens | Transaction |
|---|---|---|---|
| 1 | order | `POST /api/v1/orders`: stock pre-check (Feign + Retry → CircuitBreaker → Bulkhead, 2 s timeout) and price lookup | none: remote calls stay **outside** the DB transaction |
| 2 | order | `orders` row `PENDING` + `outbox_event` `OrderPlaced`; answers `201 {orderId, PENDING}` | one |
| 3 | order | outbox poller publishes to `order-events` (key = `orderId`, header `traceparent`) | — |
| 4 | inventory | reserves stock with an atomic conditional `UPDATE`, writes `InventoryReserved` (or `InventoryReservationFailed`) to its outbox | one |
| 5 | payment | charges once per order, writes `PaymentCompleted` / `PaymentFailed` to its outbox | one |
| 6 | order | `PENDING → CONFIRMED` (`OrderConfirmed`) or `PENDING → CANCELLED` (`OrderCancelled`) | one |
| 7 | inventory | consumes the reservation, or **releases** it on `PaymentFailed` / `OrderCancelled` (compensation) | one |
| 8 | notification | sends the confirmation or the cancellation notice | — |

### Guarantees and where they come from

| Guarantee | Mechanism | Evidence |
|---|---|---|
| An order and its event are never split | transactional outbox, poller with `FOR UPDATE SKIP LOCKED` | `OutboxPublisherTest`, `OrderServiceTest` |
| A redelivered event changes nothing (NFR-10) | `processed_event(event_id, consumer)` in the same transaction; state checks (`CONFIRMED` twice is a no-op) | `InventoryServiceTest`, `OrderServiceTest` |
| A failing consumer never silently drops a Saga step (NFR-10) | 3 retries 1 s apart, then `<topic>.DLT` + `ALERT dead-letter` log | `OrderSagaDeadLetterIT`, `InventorySagaDeadLetterIT` |
| Sold-out products never block other customers | `OutOfStockException` is not retried and not counted by the circuit breaker | `OrderSyncIntegrationTest` |
| No orphaned stock (NFR-05) | inventory sweeper (every 10 s) releases `RESERVED` rows of orders recorded as cancelled, after a 30 s grace period | `ReservationSweeperTest`, `StockRepositoryTest` |
| No order stuck forever | order sweeper cancels `PENDING` orders older than 10 min and publishes `OrderCancelled` | `PendingOrderSweeperTest` |
| One trace per order (NFR-06) | `traceparent` stored on every outbox row and sent as a Kafka header | Zipkin, see Observability |

The inventory sweeper releases by **saga outcome, never by age** (ADD Decision 6-1): releasing every reservation older
than 30 s would return stock for orders whose payment is merely slow, and sell the same unit twice.

### Demo script (uses `$CUSTOMER` / `$ADMIN` from "Security & curl checks")

```bash
GW=http://localhost:8080
# Happy path: 201 PENDING, then CONFIRMED within a second or two
ID=$(curl -s -X POST $GW/api/v1/orders -H "Authorization: Bearer $CUSTOMER" -H 'Content-Type: application/json' \
       -d '{"items":[{"productId":1,"quantity":1}]}' | sed -E 's/.*"orderId":"([^"]+)".*/\1/')
curl -s $GW/api/v1/orders/$ID -H "Authorization: Bearer $CUSTOMER"          # "status":"CONFIRMED"

# No stock: 409 OUT_OF_STOCK, no order row, payment never touched
curl -s -X POST $GW/api/v1/orders -H "Authorization: Bearer $CUSTOMER" -H 'Content-Type: application/json' \
     -d '{"items":[{"productId":1,"quantity":100000}]}'

# Compensation: every payment fails -> CANCELLED and the stock comes back
#   set PAYMENT_FAILURE_RATE=1.0 in deployment/docker/.env, then:
docker compose -f deployment/docker/docker-compose.yml up -d payment-service
curl -s $GW/api/v1/inventory/1 -H "Authorization: Bearer $ADMIN"            # note "available"
#   place an order as above -> "status":"CANCELLED"; "available" is back to the same number

# NFR-01 chaos: payment down -> order stays PENDING, nothing is lost
docker compose -f deployment/docker/docker-compose.yml stop payment-service
#   place an order -> PENDING (and stays PENDING)
docker compose -f deployment/docker/docker-compose.yml start payment-service
#   once payment has rejoined its consumer group (seconds), the same order is CONFIRMED
```

**NFR-05 query** (must print `0`):

```bash
docker compose -f deployment/docker/docker-compose.yml exec postgres psql -U postgres -d inventory_db -c \
  "SELECT count(*) FROM reservation r WHERE r.status = 'RESERVED'
     AND r.order_id IN (SELECT order_id FROM cancelled_order)
     AND r.created_at < now() - interval '30 seconds';"
```

---

## Payment
*(Maintained by Member C)*

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

---

## Kubernetes
*(Maintained by Member C)*

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

---

## Helm & GitOps
*(Maintained by Member A)*

One chart, `deployment/helm/microservice`, deploys every service; `deployment/helm/values/<service>.yaml` holds the
differences. Each pod runs as non-root (uid 10001) with a read-only root filesystem, dropped capabilities,
startup/liveness/readiness probes on `/actuator/health/*`, its own ServiceAccount without an API token, and
its secrets from `<service>-secrets` (never in Git). Details: [deployment/helm/README.md](deployment/helm/README.md).

ArgoCD deploys `infra` (raw manifests) and one Application per service (ApplicationSet), automated with
`prune` + `selfHeal`, tracking the `env/dev` branch that CI updates. Install and the drift demo:
[deployment/argocd/README.md](deployment/argocd/README.md).

---

## Observability
*(Maintained by Member B)*

| What | Where | Notes |
|---|---|---|
| Traces (NFR-06) | Zipkin <http://localhost:9411> | Micrometer Tracing (Brave), W3C `traceparent`, 100 % sampling |
| Logs | `docker compose logs <service>` | **JSON**, one object per line, with `traceId`, `spanId` and `service` |
| Metrics | `/actuator/prometheus` on every service → Prometheus <http://localhost:9090> | |
| Dashboards | Grafana <http://localhost:3000>: *Platform overview*, *Order Analytics (B2)* | |
| Dead letters | `<topic>.DLT` topics | headers name the consumer group and the exception |

### One trace across HTTP and Kafka

The request span starts at the gateway. order-service stores the current `traceparent` on the outbox row, and the
poller sends it as a Kafka header, so inventory, payment, notification and the order's own Saga consumers continue the
**same** trace (`spring.kafka.template/listener.observation-enabled`). The scheduled pollers themselves are not traced
(`management.observations.enable.spring.scheduled: false`), so they do not bury the request traces.

```bash
# 1. place an order (Orders & Saga), then take its traceId from any service's log line
docker compose -f deployment/docker/docker-compose.yml logs order-service | grep 'Order placed' | tail -1
#   {"message":"Order placed and OutboxEvent persisted: orderId=…","traceId":"6ac4…","service":"order-service",…}
# 2. open http://localhost:9411/zipkin/traces/<traceId>:
#    api-gateway -> order-service -> inventory /check, product /{id}
#    -> order-events -> inventory-service -> inventory-events -> payment-service -> payment-events
#    -> order-service (CONFIRMED) -> order-events -> notification-service
# 3. the same traceId finds every log line of that order in every service
docker compose -f deployment/docker/docker-compose.yml logs | grep '"traceId":"<traceId>"'
```

### Reading a dead-letter topic

```bash
docker compose -f deployment/docker/docker-compose.yml exec kafka kafka-console-consumer \
  --bootstrap-server kafka:29092 --topic payment-events.DLT --from-beginning --property print.headers=true
# kafka_dlt-original-consumer-group, kafka_dlt-exception-message, … tell who gave up and why
```

---

## Load tests
*(Maintained by Member A)*

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

---

## Bonus B2 — Order Analytics
*(Maintained by Member C)*

An ADMIN view of order volume, revenue and Saga failures, built from `order-events` with no new infrastructure.

- **Read model:** `analytics_order` in `order_db`, written by consumer group `order-service-analytics`. It is idempotent per `eventId` (`analytics_processed_event`) and safe when `OrderConfirmed` arrives before `OrderPlaced`.
- **API:** `GET /api/v1/analytics/summary?hours=24` (ADMIN, 1–168 hours). It returns orders by status, revenue from confirmed orders, the cancelled ratio and an hourly breakdown.
- **Metrics:** `analytics_orders_total{status="PLACED|CONFIRMED|CANCELLED"}` and `analytics_revenue_total`, incremented only after commit.
- **Dashboard:** Grafana → *Capstone / Order Analytics* (http://localhost:3000). Panels: orders per minute by status, revenue, Saga failure rate.

```bash
curl -H "Authorization: Bearer $ADMIN_TOKEN" "http://localhost:8080/api/v1/analytics/summary?hours=24"
```
