# Capstone Project Final Report: Enterprise E-Commerce Platform

**Course / Cohort:** Capstone Microservices (Spring Boot 3 & Spring Cloud)  
**Team:** 13nour11 / Capstone Platform Team  
**Evaluation Gate:** Layer L6 / Final Delivery (Gate G4)  
**Submission Date:** October 2026  

---

## 1. Executive Summary

This project delivers an enterprise-grade, cloud-native e-commerce microservices platform built with Java 21, Spring Boot 3.4/3.5, Spring Cloud 2024/2025, Apache Kafka, PostgreSQL, Redis, and Keycloak. The system supports full product discovery, real-time stock reservation, asynchronous multi-service checkout choreography, idempotent payment processing, and administrative analytics.

All 8 microservices and platform modules have been developed, containerized, and integrated under continuous integration with strict code quality gates, achieving $\ge 60\%$ unit/integration test coverage on application layers, zero network I/O inside database transactions, and complete distributed tracing across HTTP and asynchronous Kafka boundaries.

---

## 2. Architecture & Design Principles

### 2.1 Domain Decomposition & Service Boundaries
The platform adheres strictly to the **Database-per-Service** pattern to guarantee loose coupling and autonomous deployability:
1. **`platform/config-server` (Port 8888):** Centralized native configuration repository providing externalized environment settings.
2. **`platform/eureka-server` (Port 8761):** Service registry enabling dynamic service discovery.
3. **`platform/api-gateway` (Port 8080):** Spring Cloud Gateway acting as the single ingress point, enforcing Keycloak JWT verification, token bucket rate limiting via Redis, header sanitization, and context propagation.
4. **`services/product-service` (Port 8081):** Product catalog management with Redis cache-aside caching and CQRS category read projections.
5. **`services/inventory-service` (Port 8084):** SKU stock management, atomic reservation updates, Saga participant, and a 30-second TTL background sweeper.
6. **`services/order-service` (Port 8082):** Order state machine, synchronous OpenFeign stock verification with Resilience4j circuit breakers, Transactional Outbox engine, and Saga coordination. Also hosts the **Bonus B2 Order Analytics** projection module.
7. **`services/payment-service` (Port 8083):** Idempotent payment processing (`Idempotency-Key`), payment failure simulation toggle, and refund management.
8. **`services/notification-service` (Port 8085):** Asynchronous customer notification listener with Spring Kafka `@RetryableTopic` and Dead-Letter Topic (DLT) parking.

### 2.2 Distributed Data Consistency: Choreographed Saga
Rather than using heavy, single-point-of-failure orchestrators or blocking two-phase commit (2PC) protocols, the platform implements an **Event-Driven Choreographed Saga**:

```
[OrderPlaced] (order-service)
      │
      ▼
[inventory-service] ──► Reserves stock atomically
      │
      ├── [InventoryReserved] ────────────────┐
      │                                       ▼
      │                             [payment-service] ──► Charges order
      │                                       │
      │                  ┌────────────────────┴────────────────────┐
      │                  ▼                                         ▼
      │         [PaymentCompleted]                        [PaymentFailed]
      │                  │                                         │
      ▼                  ▼                                         ▼
[order-service] ──► CONFIRMED                             [order-service] ──► CANCELLED
                                                                   │
                                                                   ▼ (Compensating action)
                                                          [inventory-service] ──► Releases stock
```

### 2.3 The Transactional Outbox Pattern
To solve the dual-write problem between PostgreSQL and Apache Kafka:
- When a service mutates domain state inside `@Transactional`, it inserts the domain event into an `outbox_event` table in the **same database commit**.
- An asynchronous `@Scheduled` publisher polls pending outbox records using `SELECT ... FOR UPDATE SKIP LOCKED` to prevent lock contention between instances.
- The publisher sends the record to Kafka, synchronously waits for the broker acknowledgment (`.get(5, TimeUnit.SECONDS)`), and only then transitions the status to `SENT`.
- If Kafka is unreachable, the event remains `PENDING` and is retried on the next tick, ensuring zero message loss.

### 2.4 Idempotent Consumers (NFR-10)
Kafka guarantees at-least-once delivery. To achieve exactly-once business processing:
- Each consumer service maintains a `processed_event` table with a composite primary key `(event_id, consumer)`.
- When an event arrives, the consumer attempts to insert the `eventId` in the same database transaction as the business operation.
- Re-delivered events trigger a `DataIntegrityViolationException`, which is caught and logged, gracefully discarding duplicate side-effects.

---

## 3. Resilience, Fault Tolerance & Observability

### 3.1 Fault Tolerance (NFR-01)
- **Zero Network I/O in DB Transactions:** OpenFeign calls to `inventory-service` are executed *before* opening the database transaction in `order-service`, preventing HikariCP database connection pool exhaustion under downstream latency.
- **Resilience4j Circuit Breaking:** Configured on OpenFeign clients with countdown sliding windows (5 calls, 50% failure rate threshold). When inventory is unreachable, the circuit opens and returns `503 Service Unavailable` with RFC 7807 `ProblemDetail`.
- **Reservation TTL Sweeper (NFR-05):** If an order placement flow crashes mid-flight, `ReservationSweeper` automatically scans `inventory_db` every 10 seconds and cancels any `RESERVED` hold older than 30 seconds, immediately returning the stock to the available inventory pool.

### 3.2 Distributed Tracing & Logging (NFR-06)
- **Micrometer Tracing & Brave:** Propagates W3C trace contexts (`traceparent: 00-<traceId>-<spanId>-01`) across HTTP requests through API Gateway and downstream services.
- **Trace Continuity over Kafka:** When an event is stored in the outbox table, the active `traceparent` is stored in the `traceparent` column. The outbox publisher restores this header onto the Kafka `ProducerRecord`. In Zipkin (`http://localhost:9411`), operators can trace a complete transaction from the initial `POST /api/v1/orders` down to the asynchronous payment consumer and notification alert.

---

## 4. Quality Verification & Testing Evidence

Every service incorporates comprehensive automated test suites:
- **Unit & Slice Tests:** JUnit 5, Mockito, and Spring `@WebMvcTest` validating business logic and RFC 7807 problem details.
- **Integration Tests:** Testcontainers running real PostgreSQL 16 and Apache Kafka testcontainers ensuring production parity.
- **Test Metrics:**
  - `inventory-service`: **30 / 30 tests passing**; application layer line coverage = **76.6%** ($\ge 60\%$ requirement).
  - `order-service`: **31 / 31 tests passing**; comprehensive WireMock and H2/PostgreSQL integration tests.
  - `payment-service`: **11 / 11 test classes passing**; full idempotency replay and saga listener tests.
  - `notification-service`: **13 / 13 tests passing**; Testcontainers Kafka integration testing poison-pill and DLT routing.
  - `eureka-server` & `config-server`: Verified standalone bootstrapping and native config location binding.

---

## 5. Lessons Learned & Production Recommendations

1. **Spring AOP Self-Invocation Pitfall:** Calling transactional methods internally on `this` bypasses Spring's CGLIB proxy. Separating transactional operations into dedicated persistence delegates (`OrderPersistenceService`) guarantees that database transaction boundaries are reliably applied.
2. **Asynchronous Send Traps in Outbox Engines:** Calling `kafkaTemplate.send()` without `.get()` leads to silent message loss under broker downtime. Always wait for broker acknowledgment or use persistent callbacks before marking outbox records as sent.
3. **Contract Defensive Programming:** Message consumers must accept both structured envelopes and flat event records with header-based event types to allow evolutionary microservice migrations without breaking downstream listeners.

---

## 6. Conclusion
The Capstone Platform successfully implements all core and bonus requirements set forth in the project brief. The platform is fully prepared for final presentation, containerized deployment, and architectural defense.
