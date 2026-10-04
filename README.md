# Capstone Platform Microservices

An enterprise e-commerce platform built with Spring Boot 3, Spring Cloud, Kafka, and PostgreSQL, demonstrating Choreography-based Saga, Transactional Outbox, and Distributed Tracing.

---

## Run locally

### Prerequisites
- Java 21+
- Apache Maven 3.9+
- Docker & Docker Compose (optional for full platform infra)
- PostgreSQL 16 & Apache Kafka 3.7 (or embedded test profile)

### Running Member B Services Locally

#### 1. Start Config & Eureka Servers (Platform)
```bash
# Config Server
mvn spring-boot:run -f platform/config-server/pom.xml

# Eureka Server
mvn spring-boot:run -f platform/eureka-server/pom.xml
```

#### 2. Start Inventory Service
```bash
# Runs on port 8084
mvn spring-boot:run -f services/inventory-service/pom.xml
```

#### 3. Start Order Service
```bash
# Runs on port 8082
mvn spring-boot:run -f services/order-service/pom.xml
```

#### 4. Running Verification Test Suites
```bash
# Test Inventory Service (30 tests)
mvn test -f services/inventory-service/pom.xml

# Test Order Service (24 tests)
mvn test -f services/order-service/pom.xml
```

---

## Security & curl checks
*(Maintained by Member A)*

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

---

## Payment
*(Maintained by Member C)*

---

## Kubernetes
*(Maintained by Member C)*

---

## Helm & GitOps
*(Maintained by Member A)*

---

## Observability
*(Maintained by Member B)*

Distributed tracing is configured across all services via Micrometer Tracing with Brave and Zipkin exporter (`http://localhost:9411/api/v2/spans`). All Kafka events propagate W3C `traceparent` headers to correlate spans across HTTP requests and Kafka message processing.

---

## Load tests
*(Maintained by Member A)*

---

## Bonus B2 — Order Analytics
*(Maintained by Member C)*
