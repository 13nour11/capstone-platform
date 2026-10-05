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

Each section below has one owner (`docs/TEAM-GUIDE.md` §4.2).

---

## Run locally

### Prerequisites
- Java 21+ (JDK 21)
- Apache Maven 3.9+
- Docker & Docker Desktop (minimum 6–8 GB RAM allocated)

### Fast Start (Docker Compose)
```bash
cp deployment/docker/.env.example deployment/docker/.env
# Replace change-me passwords in deployment/docker/.env
docker compose -f deployment/docker/docker-compose.yml up -d --build
./scripts/verify-l0.sh
```

### Running Locally with Maven
```bash
# 1. Start Platform Discovery and Configuration
mvn spring-boot:run -f platform/config-server/pom.xml
mvn spring-boot:run -f platform/eureka-server/pom.xml

# 2. Start Gateway and Business Services
mvn spring-boot:run -f platform/api-gateway/pom.xml
mvn spring-boot:run -f services/product-service/pom.xml
mvn spring-boot:run -f services/inventory-service/pom.xml
mvn spring-boot:run -f services/order-service/pom.xml
mvn spring-boot:run -f services/payment-service/pom.xml
mvn spring-boot:run -f services/notification-service/pom.xml
```

---

## Security & curl checks

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

```bash
set -a; . ./deployment/docker/.env; set +a
KC=http://localhost:8180/realms/ecommerce-platform/protocol/openid-connect/token
token() { curl -s "$KC" -d grant_type=password -d client_id=api-gateway -d username="$1" \
            -d password="$KC_TEST_USER_PASSWORD" | sed -E 's/.*"access_token":"([^"]+)".*/\1/'; }
ADMIN=$(token admin); CUSTOMER=$(token customer1)

# FR-01 public, paginated -> 200
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
# FR-13 rate limit check:
curl -s -Z --parallel-max 100 -w '\n%{http_code}\n' $(printf 'http://localhost:8080/api/v1/products/1 %.0s' $(seq 100)) \
  | grep -E '^[0-9]{3}$' | sort | uniq -c
```

---

## Orders & Saga

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
   - The publisher synchronously awaits Kafka broker ACK (`.get(5, TimeUnit.SECONDS)`) before marking status `SENT`.

2. **Distributed Tracing Continuity (NFR-06)**:
   - W3C `traceparent` headers (`00-<traceId>-<spanId>-01`) are persisted in `outbox_event` and restored onto Kafka record headers, ensuring complete end-to-end trace correlation in Zipkin.

3. **Idempotent Consumer Processing (NFR-10)**:
   - Consumer services maintain a `processed_event(event_id, consumer)` table.
   - Duplicate events are rejected by the primary key unique constraint, ensuring exactly-once business effects.

4. **NFR-05 Reservation Timeout Sweeper**:
   - `inventory-service` executes a scheduled sweeper (`ReservationSweeper`) every 10 seconds.
   - Any reservation in `RESERVED` status older than 30 seconds is automatically released to prevent stock hoarding.

---

## Payment

`payment-service` (port 8083) charges each order **exactly once** and reports the result to the Saga.

| Endpoint | Access | Behaviour |
|---|---|---|
| `POST /api/v1/payments` (header `Idempotency-Key` required) | SERVICE, ADMIN | `201` charges the order. A retry with the same key replays the stored response with `Idempotent-Replayed: true`. The same key with a different body returns `422 IDEMPOTENCY_KEY_REUSED`. A new key for an already-paid order returns `200` with the existing payment. |
| `POST /api/v1/payments/{id}/refund` | ADMIN | Refunds a completed payment once; a repeat returns the same result. A declined payment returns `409 REFUND_NOT_ALLOWED`. |

**Failure switch.** `payment.simulation.failure-rate` in `config-repo/payment-service.yml` (`PAYMENT_FAILURE_RATE` in `deployment/docker/.env`):
- `0.0`: every payment succeeds.
- `1.0`: every payment fails, driving the compensation path.

---

## Kubernetes

The kind cluster and infrastructure manifests live in `deployment/kubernetes/`. The full guide is [deployment/kubernetes/README.md](deployment/kubernetes/README.md).

```bash
kind create cluster --config deployment/kubernetes/kind-config.yaml
kubectl apply -f deployment/kubernetes/infra/namespace.yaml
scripts/create-k8s-secrets.sh
kubectl apply -f deployment/kubernetes/infra/
kubectl -n ecommerce get pods -w
```

| UI | URL (kind) |
|---|---|
| Keycloak | http://localhost:8180 |
| Zipkin | http://localhost:9411 |
| Prometheus | http://localhost:9090 |
| Grafana | http://localhost:3000 |
| API Gateway (NodePort) | http://localhost:30080 |

---

## Helm & GitOps

One chart, `deployment/helm/microservice`, deploys every service; `deployment/helm/values/<service>.yaml` holds the
differences. Each pod runs as non-root (uid 10001) with a read-only root filesystem, dropped capabilities,
probes on `/actuator/health/*`, and secrets from `<service>-secrets`. Details: [deployment/helm/README.md](deployment/helm/README.md).

ArgoCD deploys `infra` (raw manifests) and one Application per service (ApplicationSet), automated with
`prune` + `selfHeal`.

---

## Observability

Distributed tracing is configured across all services via Micrometer Tracing with Brave and Zipkin exporter (`http://localhost:9411/api/v2/spans`). All Kafka events propagate W3C `traceparent` headers to correlate spans across HTTP requests and Kafka message processing.

Metrics are scraped by Prometheus at `/actuator/prometheus` and visualized in Grafana dashboards:
- **Platform Overview**: HTTP P95 latency per route, HikariCP pool saturation, JVM memory, and throughput.
- **Order Analytics**: Real-time order status, revenue, and Saga cancellation ratios.

---

## Load tests

[k6](https://k6.io) scripts live in `k6/`; tokens come from Keycloak at run time (`lib/auth.js`, renewed before expiry).

| Script | Shape | Pass criteria |
|---|---|---|
| `smoke-test.js` | 1 VU, 1 min | no errors |
| `load-test.js` | 60 catalogue req/s + 20 VUs ordering, 5 min | `GET /products` p95 < 200 ms, `POST /orders` p95 < 800 ms, ≥ 50 req/s, errors < 1 % |
| `stress-test.js` | ramp to 150 VUs, 11 min | records the breaking point |

```bash
k6 run -e K6_PASSWORD="$KC_TEST_USER_PASSWORD" k6/smoke-test.js
k6 run -e K6_PASSWORD="$KC_TEST_USER_PASSWORD" --summary-export k6/results/load.json k6/load-test.js
k6 run -e K6_PASSWORD="$KC_TEST_USER_PASSWORD" --summary-export k6/results/stress.json k6/stress-test.js
```

---

## Bonus B2 — Order Analytics

An ADMIN view of order volume, revenue and Saga failures, built from `order-events` with no new infrastructure.

- **Read model:** `analytics_order` in `order_db`, written by consumer group `order-service-analytics`. It is idempotent per `eventId` (`analytics_processed_event`).
- **API:** `GET /api/v1/analytics/summary?hours=24` (ADMIN, 1–168 hours). It returns orders by status, revenue from confirmed orders, and hourly breakdown.
- **Metrics:** `analytics_orders_total{status="PLACED|CONFIRMED|CANCELLED"}` and `analytics_revenue_total`.
- **Dashboard:** Grafana → *Capstone / Order Analytics* (http://localhost:3000).

```bash
curl -H "Authorization: Bearer $ADMIN_TOKEN" "http://localhost:8080/api/v1/analytics/summary?hours=24"
```
