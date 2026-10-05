# Capstone Backlog

> Brief §10.2: 12–15 stories, none larger than 4 h, every layer L1–L6 covered, every story with an observable check.
> Estimates: S = 1 h, M = 2 h, L = 4 h. Owners: A = Nourhan Fahmy, B = Mohammed Selim, C = Doaa Amr
> (from the git log; correct the mapping if it is wrong). Scope-cutting order if late (Brief §6):
> Stretch → Bonus Could stories → FR-14 → FR-13 → FR-12 → reduce Bonus Core. Never cut tests, security rules,
> the compensation path or deployment.

---

**ID:** L1-01 · **Layer:** L1 · **Gate:** G1 · **Owner:** A · **Estimate:** L (4 h)
As an admin, I want the gateway to validate Keycloak tokens and apply role rules so that only admins change the catalogue.
**Definition of Done:** `GatewaySecurityTest` green: public GET 200, POST without token 401, CUSTOMER POST 403, ADMIN POST 200.
**Depends on:** —

**ID:** L1-02 · **Layer:** L1 · **Gate:** G1 · **Owner:** A · **Estimate:** L (4 h)
As a shopper, I want to browse a paginated catalogue with category names so that I can find products without signing in.
**Definition of Done:** `ProductControllerTest` + `ProductRepositoryIT` green (Flyway V1–V2, projection with `categoryName`, page size ≤ 50).
**Depends on:** L1-01

**ID:** L1-03 · **Layer:** L1 · **Gate:** G1 · **Owner:** A · **Estimate:** M (2 h)
As a shopper, I want catalogue reads cached and public routes rate limited so that browsing stays fast and fair.
**Definition of Done:** `ProductCacheIT` (2nd read from Redis, every write evicts) and `RateLimitIT` (429 `RATE_LIMITED`) green.
**Depends on:** L1-02

**ID:** L2-01 · **Layer:** L2 · **Gate:** G1 · **Owner:** B · **Estimate:** L (4 h)
As an admin, I want to read and adjust stock so that the shop never sells what it does not have.
**Definition of Done:** `InventoryControllerTest` green: ADMIN GET/PUT 200, CUSTOMER 403, `/check` SERVICE only; `StockRepositoryTest` (Testcontainers) green.
**Depends on:** —

**ID:** L2-02 · **Layer:** L2 · **Gate:** G1 · **Owner:** B · **Estimate:** L (4 h)
As a customer, I want my order checked against stock before it is accepted so that I am told at once when an item is unavailable.
**Definition of Done:** `OrderSyncIntegrationTest` green: 201 PENDING, 409 `OUT_OF_STOCK`, inventory error or timeout → 503 `STOCK_CHECK_UNAVAILABLE` (CB + Retry + Bulkhead + TimeLimiter).
**Depends on:** L2-01

**ID:** L2-03 · **Layer:** L2 · **Gate:** G1 · **Owner:** C · **Estimate:** L (4 h)
As the platform, I want each payment request processed once per idempotency key so that a retried request never charges twice.
**Definition of Done:** `PaymentIdempotencyIT` green: same key twice → one row and the same response; same key, other body → 422.
**Depends on:** —

**ID:** L3-01 · **Layer:** L3 · **Gate:** G2 · **Owner:** B · **Estimate:** L (4 h)
As operations, I want order and inventory events written through a transactional outbox so that no Saga step is lost.
**Definition of Done:** `OutboxPublisherTest` (order, inventory) green: row marked SENT only after the broker ack; `OrderServiceTest` shows order + `OrderPlaced` in one transaction.
**Depends on:** L2-02

**ID:** L3-02 · **Layer:** L3 · **Gate:** G2 · **Owner:** B + C · **Estimate:** L (4 h)
As a customer, I want my order confirmed on payment success and cancelled with stock released on failure so that I am never charged without goods or left with blocked stock.
**Definition of Done:** `InventoryServiceTest`, `PaymentSagaKafkaIT`, `DeclinedPaymentIT` green; demo: happy path CONFIRMED, `PAYMENT_FAILURE_RATE=1.0` → CANCELLED + NFR-05 query returns 0.
**Depends on:** L3-01

**ID:** L3-03 · **Layer:** L3 · **Gate:** G2 · **Owner:** A · **Estimate:** M (2 h)
As a customer, I want a confirmation or a cancel notice, retried if sending fails, so that I always learn what happened to my order.
**Definition of Done:** `NotificationKafkaIT` green: notice sent, duplicate ignored, 4 attempts then `order-events.DLT` + alert log.
**Depends on:** L3-02

**ID:** L4-01 · **Layer:** L4 · **Gate:** G2 · **Owner:** C · **Estimate:** L (4 h)
As the team, I want CI to test every module and push images tagged with the commit sha so that every deployment is traceable.
**Definition of Done:** GitHub Actions run green on `main`: gitleaks, `mvn verify` per module with the JaCoCo 60 % gate, `ghcr.io/<repo>/<service>:<sha>` pushed.
**Depends on:** —

**ID:** L4-02 · **Layer:** L4 · **Gate:** G2 · **Owner:** A + C · **Estimate:** L (4 h)
As the team, I want the platform deployed to kind by ArgoCD from Helm so that drift is healed automatically.
**Definition of Done:** `kubectl -n argocd get applications` all Synced/Healthy; `kubectl scale` on a service is reverted; pods run as uid 10001 with their own ServiceAccount.
**Depends on:** L4-01

**ID:** L5-01 · **Layer:** L5 · **Gate:** G3 · **Owner:** B · **Estimate:** M (2 h)
As operations, I want one trace across HTTP and Kafka and JSON logs with the traceId so that I can follow one order end to end.
**Definition of Done:** Zipkin shows one traceId spanning gateway → order → inventory → payment → order → notification (screenshot in `docs/`); log lines are JSON with `traceId`.
**Depends on:** L3-02

**ID:** L5-02 · **Layer:** L5 · **Gate:** G3 · **Owner:** A · **Estimate:** L (4 h)
As the team, I want smoke, load and stress results with one bottleneck fixed so that we can prove the latency targets.
**Definition of Done:** `docs/PERFORMANCE-REPORT.md` filled from real k6 runs: GET products p95 < 200 ms, POST orders p95 < 800 ms at 20 VUs, ≥ 50 req/s, before/after numbers for one fix.
**Depends on:** L4-02, L5-01

**ID:** L6-01 · **Layer:** L6 · **Gate:** G4 · **Owner:** C · **Estimate:** L (4 h)
As operations, I want an analytics summary and a Grafana dashboard of orders, revenue and Saga failures (primary Bonus B2) so that I see the business without querying databases.
**Definition of Done:** `OrderAnalyticsProjectorIT` (no double count) + `AnalyticsControllerTest` green; dashboard *Order Analytics* shows 3 panels.
**Depends on:** L3-02

**ID:** L6-02 · **Layer:** L6 · **Gate:** G4 · **Owner:** Team · **Estimate:** L (4 h)
As a reviewer, I want the extra Bonuses (B1 reviews, B3 tenants, B4 low-stock alerts), the final ADD, README and a rehearsed demo so that a stranger can run and question the product.
**Definition of Done:** `ReviewControllerTest`, `SubmitReviewServiceIT`, tenant tests in `GatewaySecurityTest`/`ProductCacheIT`, `NotificationKafkaIT` (alert < 2 s) green; ADD 8 sections; demo rehearsed (Brief §12).
**Depends on:** L6-01

---

## Appendix — Member B task log (original, kept as written)

| Task ID | Day | Description | Status | Verification & Deliverables |
|:---:|:---:|:---|:---:|:---|
| **L0** | Day 1 | **Platform Foundations**: Spring Cloud Config Server (port 8888, native profile serving `config-repo`), Spring Cloud Netflix Eureka Server (port 8761, standalone registration), and `scripts/verify-l0.sh` | **DONE** | Context load tests passing for both `config-server` and `eureka-server`. `verify-l0.sh` implemented. |
| **B1** | Day 2 | **Inventory Service Core**: Flyway migrations (stock, reservation), `GET /api/v1/inventory/check`, admin endpoints `GET/PUT /api/v1/inventory/{productId}`, optimistic locking, unit/slice tests | **DONE** | Seed stock initialized. RFC 7807 problem details implemented. |
| **B2** | Day 3 | **Order Service Synchronous Flow**: Flyway migrations `V1` (`orders`, `order_items`), `POST /api/v1/orders`, `GET /api/v1/orders/{id}`, `GET /api/v1/orders`, OpenFeign stock check + Resilience4j circuit breaker/retry/timelimiter, WireMock integration tests | **DONE** | 19 tests passing (409 `OUT_OF_STOCK`, 503 `STOCK_CHECK_UNAVAILABLE`, 201 Created). |
| **B3** | Day 4 | **Transactional Outbox Engine in Order Service**: `outbox_event` (Flyway `V2`) and `processed_event` (Flyway `V3`), atomic dual-write inside `@Transactional`, `@Scheduled` poller with `SKIP LOCKED`, Kafka `ProducerRecord` with W3C `traceparent` and `eventType` headers | **DONE** | Outbox publisher tests passing. Zero Kafka send calls inside `@Transactional`. |
| **B4** | Day 4 | **Inventory Saga Participant**: Reserve stock on `OrderPlaced`, compensation release on `PaymentFailed` or `OrderCancelled`, consume confirmation on `PaymentCompleted` / `OrderConfirmed`, transactional outbox for `InventoryReserved` / `InventoryReservationFailed`, NFR-05 reservation sweeper (30s TTL) | **DONE** | 30 tests passing in `inventory-service` including Saga listeners, compensation, and sweeper. |
| **B5** | Day 5 | **Order Saga Consumers**: Consumer listening to `payment-events` (`PaymentCompleted` $\rightarrow$ `confirmOrder`, `PaymentFailed` $\rightarrow$ `cancelOrder`) and `inventory-events` (`InventoryReservationFailed` $\rightarrow$ `cancelOrder`), idempotent state transitions via `processed_event` | **DONE** | 24 tests passing in `order-service` including Kafka consumers and state transitions. |
| **B6** | Day 5 | **Full Saga Integration**: End-to-end happy path, inventory exhaustion failure path, and payment decline failure path | **READY** | Unit and slice integration tests fully verified with mock events and WireMock. Ready for joint run on `main`. |
| **B7** | Day 6 | **Distributed Tracing & Centralized Logging**: Unified Brave/Micrometer W3C tracing, Zipkin spans, structured log pattern with `[service,traceId,spanId]`, outbox `traceparent` propagation across Kafka | **DONE** | Configured in `config-repo/application.yml` inside `# --- tracing/logging (B) ---` and `# --- kafka (B) ---`. |
| **B8** | Day 6 | **NFR Verification & Observability**: NFR-01 resilience check & NFR-05 reservation sweep query | **READY** | Automated sweeper implemented in B4; chaos recovery verified by design. |
| **B9** | Day 7 | **Architecture Documentation**: ADD sections 3, 5, 6 and Gate G4 Final Report (`docs/FINAL-REPORT.md`) | **IN PROGRESS** | Documentation aligned with Capstone requirements. |

*Integration notes (merge of A + B + C):* the 30-second TTL sweeper (B4) now releases only reservations of cancelled
orders, so a slow payment no longer loses stock; reservations are one row per order line; the outbox stores the real
`traceparent` and marks a row SENT only after the broker ack (B3, B7). See the ADD §4–§6.
