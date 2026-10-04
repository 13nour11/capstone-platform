# capstone-platform
Microservice

## Payment

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

## Bonus B2 — Order Analytics

An ADMIN view of order volume, revenue and Saga failures, built from `order-events` with no new infrastructure.

- **Read model:** `analytics_order` in `order_db`, written by consumer group `order-service-analytics`. It is idempotent per `eventId` (`analytics_processed_event`) and safe when `OrderConfirmed` arrives before `OrderPlaced`.
- **API:** `GET /api/v1/analytics/summary?hours=24` (ADMIN, 1–168 hours). It returns orders by status, revenue from confirmed orders, the cancelled ratio and an hourly breakdown.
- **Metrics:** `analytics_orders_total{status="PLACED|CONFIRMED|CANCELLED"}` and `analytics_revenue_total`, incremented only after commit.
- **Dashboard:** Grafana → *Capstone / Order Analytics* (http://localhost:3000). Panels: orders per minute by status, revenue, Saga failure rate.

```bash
curl -H "Authorization: Bearer $ADMIN_TOKEN" "http://localhost:8080/api/v1/analytics/summary?hours=24"
```
