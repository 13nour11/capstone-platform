# Capstone Sprint Backlog

## Member B Tasks & Progress (Inventory · Order · Saga · Observability)

| Task ID | Day | Description | Status | Verification & Deliverables |
|:---:|:---:|:---|:---:|:---|
| **B1** | Day 2 | **Inventory Service Core**: Flyway migrations (stock, reservation), `GET /api/v1/inventory/check`, admin endpoints `GET/PUT /api/v1/inventory/{productId}`, optimistic locking, unit/slice tests | **DONE** | 19 tests passing. Seed stock initialized. RFC 7807 problem details implemented. |
| **B2** | Day 3 | **Order Service Synchronous Flow**: Flyway migrations `V1` (`orders`, `order_items`), `POST /api/v1/orders`, `GET /api/v1/orders/{id}`, `GET /api/v1/orders`, OpenFeign stock check + Resilience4j circuit breaker/retry/timelimiter, WireMock integration tests | **DONE** | 19 tests passing (409 `OUT_OF_STOCK`, 503 `STOCK_CHECK_UNAVAILABLE`, 201 Created). |
| **B3** | Day 4 | **Transactional Outbox Engine in Order Service**: `outbox_event` (Flyway `V2`) and `processed_event` (Flyway `V3`), atomic dual-write inside `@Transactional`, `@Scheduled` poller with `SKIP LOCKED`, Kafka `ProducerRecord` with W3C `traceparent` and `eventType` headers | **DONE** | Outbox publisher tests passing. Zero Kafka send calls inside `@Transactional`. |
| **B4** | Day 4 | **Inventory Saga Participant**: Reserve stock on `OrderPlaced`, compensation release on `PaymentFailed` or `OrderCancelled`, consume confirmation on `PaymentCompleted` / `OrderConfirmed`, transactional outbox for `InventoryReserved` / `InventoryReservationFailed`, NFR-05 reservation sweeper (30s TTL) | **DONE** | 30 tests passing in `inventory-service` including Saga listeners, compensation, and sweeper. |
| **B5** | Day 5 | **Order Saga Consumers**: Consumer listening to `payment-events` (`PaymentCompleted` $\rightarrow$ `confirmOrder`, `PaymentFailed` $\rightarrow$ `cancelOrder`) and `inventory-events` (`InventoryReservationFailed` $\rightarrow$ `cancelOrder`), idempotent state transitions via `processed_event` | **DONE** | 24 tests passing in `order-service` including Kafka consumers and state transitions. |
| **B6** | Day 5 | Full Saga Integration on `main`: End-to-end happy path, inventory exhaustion failure path, and payment decline failure path | **READY** | Unit and slice integration tests fully verified with mock events and WireMock. |
| **B7** | Day 6 | Distributed Tracing continuity across HTTP & Kafka via W3C `traceparent` in outbox, JSON logs with `traceId` | **IN PROGRESS** | Outbox schema and headers propagate `traceparent`. |
| **B8** | Day 6 | NFR-01 resilience check & NFR-05 sweep verification | **PLANNED** | Automated sweeper implemented in B4. |
| **B9** | Day 7 | Final Report & ADD architecture documentation | **PLANNED** | Documentation aligned with Capstone requirements. |
