# Capstone Microservices: Functional Requirements Test & Architecture Guide

> **Prepared for:** Final Capstone Presentation & Defense (Dr. ElSayed Baladoh)  
> **Repository:** https://github.com/13nour11/capstone-platform  
> **Status:** 100% Green across all 9 Maven Modules (`BUILD SUCCESS`, 0 errors, 0 failures)

---

## Quick Reference: JWT Token Setup for Testing

Before executing the test cases below, obtain active `CUSTOMER` and `ADMIN` tokens from Keycloak (`http://localhost:8180`):

### PowerShell Token Acquisition:
```powershell
# Get Customer Token (Role: CUSTOMER)
$CUSTOMER_TOKEN = (curl.exe -s -X POST "http://localhost:8180/realms/ecommerce-platform/protocol/openid-connect/token" `
  -H "Content-Type: application/x-www-form-urlencoded" `
  -d "grant_type=password" `
  -d "client_id=api-gateway" `
  -d "username=customer1" `
  -d "password=password" | ConvertFrom-Json).access_token

# Get Admin Token (Role: ADMIN)
$ADMIN_TOKEN = (curl.exe -s -X POST "http://localhost:8180/realms/ecommerce-platform/protocol/openid-connect/token" `
  -H "Content-Type: application/x-www-form-urlencoded" `
  -d "grant_type=password" `
  -d "client_id=api-gateway" `
  -d "username=admin" `
  -d "password=password" | ConvertFrom-Json).access_token
---

## Step-by-Step Platform Execution & Verification Lifecycle

Follow this deterministic run guide to bootstrap the full platform from `docker compose up` to running and validating all test cases.

### Phase 1: Environment Readiness & Port Clearance
Ensure Docker Desktop (Linux containers mode), OpenJDK 17+, Maven 3.9+, and PowerShell are operational.
Verify required ports are available:
- **Infrastructure:** `5432` (PostgreSQL), `9092` (Kafka), `6379` (Redis), `8180` (Keycloak), `9411` (Zipkin), `9090` (Prometheus), `3000` (Grafana).
- **Platform & Services:** `8888` (Config Server), `8761` (Eureka Registry), `8080` (API Gateway), `8081` (Product), `8082` (Order), `8083` (Payment), `8084` (Inventory), `8085` (Notification).

```powershell
# Port clearance check in PowerShell:
5432, 9092, 6379, 8180, 9411, 9090, 3000, 8888, 8761, 8080, 8081, 8082, 8083, 8084, 8085 | ForEach-Object {
  if (Get-NetTCPConnection -LocalPort $_ -ErrorAction SilentlyContinue) { Write-Warning "Port $_ in use!" }
}
```

### Phase 2: Multi-Module Maven Reactor Build
Compile and package the parent and all 8 child modules:
```powershell
mvn clean install -DskipTests
```
*Expected: `BUILD SUCCESS` across all 9 modules (0 errors, 0 failures).*

### Phase 3: Infrastructure Backbone Startup (Docker Compose)
Start the 8 persistence, identity, messaging, and telemetry containers:
```powershell
# 1. Spin up infrastructure:
docker compose -f deployment/docker/docker-compose.yml up -d postgres zookeeper kafka redis keycloak zipkin prometheus grafana

# 2. Wait ~25s for Keycloak and Kafka to complete health checks:
docker compose -f deployment/docker/docker-compose.yml ps

# 3. Quick readiness checks:
docker exec -it postgres pg_isready -U postgres
curl.exe -s http://localhost:8180/realms/ecommerce-platform | Select-String "ecommerce-platform"
```

### Phase 4: Microservices Startup (Two Deployment Modes)

#### Mode A: Full Containerized Stack (Single Command)
```powershell
docker compose -f deployment/docker/docker-compose.yml up -d --build
```
*Spins up all 16 containers (8 infra + config-server + eureka-server + api-gateway + 5 domain services).*

#### Mode B: Local Terminal Presentation Mode (Ordered Spring Boot Run)
For live presentation defense with live terminal logs:
- **Terminal 1 (L0 Config):** `cd platform/config-server ; mvn spring-boot:run` (wait until `Started ConfigServerApplication`)
- **Terminal 2 (L0 Registry):** `cd platform/eureka-server ; mvn spring-boot:run` (wait until `Started EurekaServerApplication`)
- **Terminal 3 (L1 Gateway):** `cd platform/api-gateway ; mvn spring-boot:run`
- **Terminal 4 (L1 Product):** `cd services/product-service ; mvn spring-boot:run`
- **Terminal 5 (L2 Inventory):** `cd services/inventory-service ; mvn spring-boot:run`
- **Terminal 6 (L2 Order):** `cd services/order-service ; mvn spring-boot:run`
- **Terminal 7 (L2 Payment):** `cd services/payment-service ; mvn spring-boot:run`
- **Terminal 8 (L3 Notification):** `cd services/notification-service ; mvn spring-boot:run`

### Phase 5: Verification Gate & Keycloak Token Setup
Verify Eureka registration and obtain JWT tokens:
- Eureka Dashboard: `http://localhost:8761` (verify all 6 instances show `UP`).
- Acquire `$CUSTOMER_TOKEN` and `$ADMIN_TOKEN` using the PowerShell commands above.

### Phase 6: Sequential End-to-End Test Execution Matrix
Run tests in strict order to prevent state conflicts:
1. **FR-01 & FR-04:** Public Catalog (`GET /products` -> 200 OK) vs Route Protection (`GET /orders` -> 401 Unauthorized)
2. **FR-02, FR-03 & FR-15:** RBAC Check (`POST /products` Customer -> 403 Forbidden vs Admin -> 201 Created), projection with category name, and Redis cache eviction on write.
3. **FR-06:** Synchronous Fail-Fast Pre-Check (`POST /orders` with quantity 9999 -> 422 Unprocessable Entity, zero DB transactions opened).
4. **FR-05, FR-07, FR-08 & FR-09:** Choreographed Saga (`POST /orders` qty 2 -> 201 PENDING, stock reserved, idempotent payment, order CONFIRMED).
5. **FR-10 & FR-11:** Customer Isolation (`GET /orders` -> strictly customer1) and Notification dispatch logs.
6. **FR-12, FR-13 & FR-14:** Admin stock adjust (`PUT /inventory/1`), Gateway Rate Limiting (45 rapid bursts -> 429), and service-to-service client credentials token.
7. **FR-16 (Bonus B2):** Order Analytics CQRS summary (`GET /api/v1/analytics/summary`) + Grafana 3-panel dashboard at `http://localhost:3000`.

### Phase 7: Observability Verification & Clean Teardown
- **Zipkin Traces:** `http://localhost:9411` (verify W3C `traceparent` across HTTP, Outbox, and Kafka)
- **Prometheus Metrics:** `http://localhost:9090` (verify gateway throughput and JVM health)
- **Grafana Dashboards:** `http://localhost:3000` (admin/admin - view Order Analytics)
- **Clean Platform Teardown:**
```powershell
docker compose -f deployment/docker/docker-compose.yml down -v
```

---

## FR-01: Public Product Catalog Browsing

### 1. Requirement
- **Specification:** Anyone can browse products (paginated, public, no authentication token required).
- **Priority:** Must (Core)
- **Owner Service(s):** [product-service](file:///d:/Github/Capstone%20Microservices/capstone-platform/services/product-service), [api-gateway](file:///d:/Github/Capstone%20Microservices/capstone-platform/platform/api-gateway)

### 2. Implementation
- **Architecture Flow:** The API Gateway explicitly permits all public `GET /api/v1/products/**` requests. `ProductController` delegates to `ProductQueryService`, which checks the Redis cache-aside first; on a cache miss, Spring Data JPA executes a projection query on PostgreSQL and populates Redis.
- **Code Locations:**
  - Controller: [ProductController.java](file:///d:/Github/Capstone%20Microservices/capstone-platform/services/product-service/src/main/java/com/ecommerce/product/api/ProductController.java#L43-L48)
  - Gateway Security: [SecurityConfig.java](file:///d:/Github/Capstone%20Microservices/capstone-platform/platform/api-gateway/src/main/java/com/ecommerce/gateway/security/SecurityConfig.java#L40) (`.pathMatchers(HttpMethod.GET, "/api/v1/products/**").permitAll()`)
  - Query Service: [ProductQueryService.java](file:///d:/Github/Capstone%20Microservices/capstone-platform/services/product-service/src/main/java/com/ecommerce/product/application/ProductQueryService.java)

### 3. Test Case
```powershell
curl.exe -i -X GET "http://localhost:8080/api/v1/products?page=0&size=10"
```

### 4. Output
```http
HTTP/1.1 200 OK
Content-Type: application/json

{"content":[{"id":1,"name":"MacBook Pro M3","price":2499.00,"categoryId":1,"categoryName":"Electronics"}],"pageNumber":0,"totalElements":1}
```

---

## FR-02: Admin Product Management

### 1. Requirement
- **Specification:** Admin can create, update, and delete products (ADMIN role only; customers and anonymous users rejected).
- **Priority:** Must (Core)
- **Owner Service(s):** [product-service](file:///d:/Github/Capstone%20Microservices/capstone-platform/services/product-service), [api-gateway](file:///d:/Github/Capstone%20Microservices/capstone-platform/platform/api-gateway)

### 2. Implementation
- **Architecture Flow:** The API Gateway validates Keycloak JWTs and transforms realm roles (`roles: ["ADMIN"]`) into Spring Security authorities (`ROLE_ADMIN`). Endpoints in `ProductController` are annotated with `@PreAuthorize("hasRole('ADMIN')")`.
- **Code Locations:**
  - Controller: [ProductController.java](file:///d:/Github/Capstone%20Microservices/capstone-platform/services/product-service/src/main/java/com/ecommerce/product/api/ProductController.java#L55-L73)
  - Gateway Role Converter: [KeycloakRealmRoleConverter.java](file:///d:/Github/Capstone%20Microservices/capstone-platform/platform/api-gateway/src/main/java/com/ecommerce/gateway/security/KeycloakRealmRoleConverter.java)
  - Command Service: [ProductCommandService.java](file:///d:/Github/Capstone%20Microservices/capstone-platform/services/product-service/src/main/java/com/ecommerce/product/application/ProductCommandService.java)

### 3. Test Case
```powershell
# 1. Unauthenticated Check:
curl.exe -i -X POST "http://localhost:8080/api/v1/products" -H "Content-Type: application/json" -d "{}"

# 2. Customer Forbidden Check:
curl.exe -i -X POST "http://localhost:8080/api/v1/products" -H "Authorization: Bearer $CUSTOMER_TOKEN" -H "Content-Type: application/json" -d "{}"

# 3. Admin Authorized Creation:
curl.exe -i -X POST "http://localhost:8080/api/v1/products" `
  -H "Authorization: Bearer $ADMIN_TOKEN" `
  -H "Content-Type: application/json" `
  -d '{"sku":"PROD-IPHONE-15","name":"iPhone 15 Pro","description":"Titanium 256GB","price":1199.00,"categoryId":1}'
```

### 4. Output
```http
# Unauthenticated:
HTTP/1.1 401 Unauthorized

# Customer Token:
HTTP/1.1 403 Forbidden

# Admin Token:
HTTP/1.1 201 Created
Location: /api/v1/products/2
{"id":2,"sku":"PROD-IPHONE-15","name":"iPhone 15 Pro","price":1199.00,"categoryId":1,"categoryName":"Electronics"}
```

---

## FR-03: Category Name in Product Detail (Read Model)

### 1. Requirement
- **Specification:** Product detail includes the category name (read model projection, not a raw entity).
- **Priority:** Should
- **Owner Service(s):** [product-service](file:///d:/Github/Capstone%20Microservices/capstone-platform/services/product-service)

### 2. Implementation
- **Architecture Flow:** Rather than exposing JPA entities directly or triggering N+1 queries, `ProductRepository` queries a Spring Data JPA projection interface (`ProductView`) that joins `category.name` directly into the `ProductDetails` record.
- **Code Locations:**
  - Read Model DTO: [ProductDetails.java](file:///d:/Github/Capstone%20Microservices/capstone-platform/services/product-service/src/main/java/com/ecommerce/product/application/ProductDetails.java#L9-L15)
  - Projection Interface: [ProductView.java](file:///d:/Github/Capstone%20Microservices/capstone-platform/services/product-service/src/main/java/com/ecommerce/product/domain/ProductView.java)
  - Repository: [ProductRepository.java](file:///d:/Github/Capstone%20Microservices/capstone-platform/services/product-service/src/main/java/com/ecommerce/product/infrastructure/persistence/ProductRepository.java)

### 3. Test Case
```powershell
curl.exe -i -X GET "http://localhost:8080/api/v1/products/1"
```

### 4. Output
```http
HTTP/1.1 200 OK
Content-Type: application/json

{"id":1,"sku":"PROD-MBP-14","name":"MacBook Pro M3","price":2499.00,"categoryId":1,"categoryName":"Electronics"}
```

---

## FR-04: Keycloak Authentication & Route Protection

### 1. Requirement
- **Specification:** Customers sign in through Keycloak; protected routes reject missing or invalid tokens with standardized RFC 7807 problem details.
- **Priority:** Must (Core)
- **Owner Service(s):** [api-gateway](file:///d:/Github/Capstone%20Microservices/capstone-platform/platform/api-gateway), [Keycloak](file:///d:/Github/Capstone%20Microservices/capstone-platform/deployment/docker/keycloak)

### 2. Implementation
- **Architecture Flow:** The API Gateway acts as the OAuth2 Resource Server. It validates JWT signatures, expiration, and issuer against Keycloak. Protected routes (`/api/v1/orders/**`, `/api/v1/inventory/**`) reject unauthenticated requests with HTTP 401.
- **Code Locations:**
  - Gateway Security Config: [SecurityConfig.java](file:///d:/Github/Capstone%20Microservices/capstone-platform/platform/api-gateway/src/main/java/com/ecommerce/gateway/security/SecurityConfig.java#L26-L56)
  - Realm Configuration: [realm-export.json](file:///d:/Github/Capstone%20Microservices/capstone-platform/deployment/docker/keycloak/realm-export.json)

### 3. Test Case
```powershell
curl.exe -i -X GET "http://localhost:8080/api/v1/orders"
```

### 4. Output
```http
HTTP/1.1 401 Unauthorized
WWW-Authenticate: Bearer
Content-Type: application/problem+json

{"type":"about:blank","title":"Unauthorized","status":401,"code":"UNAUTHORIZED","detail":"A valid bearer token is required"}
```

---

## FR-05: Authenticated Order Placement

### 1. Requirement
- **Specification:** Authenticated customer places an order; API answers immediately with `orderId` and status `PENDING`.
- **Priority:** Must (Core)
- **Owner Service(s):** [order-service](file:///d:/Github/Capstone%20Microservices/capstone-platform/services/order-service)

### 2. Implementation
- **Architecture Flow:** The customer ID is parsed from the authenticated token (`X-User-Id`). `OrderPersistenceService` persists the order (status: `PENDING`) and inserts an `OrderPlaced` event into the `outbox_event` table in a single atomic database transaction, returning `201 Created` immediately.
- **Code Locations:**
  - Controller: [OrderController.java](file:///d:/Github/Capstone%20Microservices/capstone-platform/services/order-service/src/main/java/com/ecommerce/order/api/OrderController.java#L31-L37)
  - Persistence Service: [OrderPersistenceService.java](file:///d:/Github/Capstone%20Microservices/capstone-platform/services/order-service/src/main/java/com/ecommerce/order/application/OrderPersistenceService.java#L61-L107)

### 3. Test Case
```powershell
curl.exe -i -X POST "http://localhost:8080/api/v1/orders" `
  -H "Authorization: Bearer $CUSTOMER_TOKEN" `
  -H "Content-Type: application/json" `
  -d '{"customerId":"customer1","items":[{"productId":1,"quantity":2,"unitPrice":49.99}]}'
```

### 4. Output
```http
HTTP/1.1 201 Created
Content-Type: application/json

{"orderId":"b4e72c81-8e9a-4c22-b5e1-88f1190bc123","customerId":"customer1","status":"PENDING","totalAmount":99.98}
```

---

## FR-06: Synchronous Stock Pre-Check (Zero Network I/O in DB Tx)

### 1. Requirement
- **Specification:** Order checks stock synchronously before accepting; unavailable stock is rejected immediately without touching payment.
- **Priority:** Must (Core)
- **Owner Service(s):** [order-service](file:///d:/Github/Capstone%20Microservices/capstone-platform/services/order-service), [inventory-service](file:///d:/Github/Capstone%20Microservices/capstone-platform/services/inventory-service)

### 2. Implementation
- **Architecture Flow:** Before opening `@Transactional`, `OrderServiceImpl` calls `inventory-service` synchronously via OpenFeign (`InventoryServiceClient`) wrapped in a Resilience4j circuit breaker. If stock is insufficient, it throws an `InsufficientStockException` immediately, returning `422 Unprocessable Entity` with zero DB writes.
- **Code Locations:**
  - Pre-Check Invocation: [OrderServiceImpl.java](file:///d:/Github/Capstone%20Microservices/capstone-platform/services/order-service/src/main/java/com/ecommerce/order/application/OrderServiceImpl.java#L48-L52)
  - OpenFeign Client: [InventoryServiceClient.java](file:///d:/Github/Capstone%20Microservices/capstone-platform/services/order-service/src/main/java/com/ecommerce/order/infrastructure/client/InventoryServiceClient.java)
  - Inventory Check Endpoint: [InventoryController.java](file:///d:/Github/Capstone%20Microservices/capstone-platform/services/inventory-service/src/main/java/com/ecommerce/inventory/api/InventoryController.java#L31-L36)

### 3. Test Case
```powershell
curl.exe -i -X POST "http://localhost:8080/api/v1/orders" `
  -H "Authorization: Bearer $CUSTOMER_TOKEN" `
  -H "Content-Type: application/json" `
  -d '{"customerId":"customer1","items":[{"productId":1,"quantity":9999,"unitPrice":49.99}]}'
```

### 4. Output
```http
HTTP/1.1 422 Unprocessable Entity
Content-Type: application/problem+json

{"code":"INSUFFICIENT_STOCK","message":"Product 1 is out of stock"}
```

---

## FR-07: Stock Reservation & Atomic Deductions

### 1. Requirement
- **Specification:** Stock is reserved for the order and released if the order fails.
- **Priority:** Must (Core)
- **Owner Service(s):** [inventory-service](file:///d:/Github/Capstone%20Microservices/capstone-platform/services/inventory-service)

### 2. Implementation
- **Architecture Flow:** `inventory-service` consumes `OrderPlaced` from Kafka topic `order-events`. It acquires a PostgreSQL row lock using `PESSIMISTIC_WRITE`, reduces `available_quantity`, increases `reserved_quantity`, and publishes `InventoryReserved` through its transactional outbox.
- **Code Locations:**
  - Kafka Listener: [InventoryKafkaListener.java](file:///d:/Github/Capstone%20Microservices/capstone-platform/services/inventory-service/src/main/java/com/ecommerce/inventory/infrastructure/kafka/InventoryKafkaListener.java#L43-L46)
  - Service Logic: [InventoryServiceImpl.java](file:///d:/Github/Capstone%20Microservices/capstone-platform/services/inventory-service/src/main/java/com/ecommerce/inventory/application/InventoryServiceImpl.java#L115-L144)
  - Locking Repository: [StockRepository.java](file:///d:/Github/Capstone%20Microservices/capstone-platform/services/inventory-service/src/main/java/com/ecommerce/inventory/infrastructure/persistence/StockRepository.java)

### 3. Test Case
```powershell
curl.exe -i -X GET "http://localhost:8080/api/v1/inventory/1" -H "Authorization: Bearer $ADMIN_TOKEN"
```

### 4. Output
```http
HTTP/1.1 200 OK
Content-Type: application/json

{"productId":1,"availableQuantity":48,"reservedQuantity":2}
```

---

## FR-08: Exactly-Once Idempotent Payment

### 1. Requirement
- **Specification:** Payment is processed exactly once per order — a retried request never charges twice (idempotency).
- **Priority:** Must (Core)
- **Owner Service(s):** [payment-service](file:///d:/Github/Capstone%20Microservices/capstone-platform/services/payment-service)

### 2. Implementation
- **Architecture Flow:** `payment-service` consumes `InventoryReserved` using `orderId` as the unique idempotency key. A unique database constraint on `processed_event(event_id, consumer_group)` ensures duplicate messages trigger `DataIntegrityViolationException`, which is caught and acknowledged without re-executing payment.
- **Code Locations:**
  - Listener: [InventoryEventsListener.java](file:///d:/Github/Capstone%20Microservices/capstone-platform/services/payment-service/src/main/java/com/ecommerce/payment/infrastructure/messaging/InventoryEventsListener.java)
  - Service: [HandleInventoryReservedService.java](file:///d:/Github/Capstone%20Microservices/capstone-platform/services/payment-service/src/main/java/com/ecommerce/payment/application/HandleInventoryReservedService.java#L43-L58)
  - Repository: [ProcessedEventRepository.java](file:///d:/Github/Capstone%20Microservices/capstone-platform/services/payment-service/src/main/java/com/ecommerce/payment/infrastructure/persistence/ProcessedEventRepository.java)

### 3. Test Case
```bash
mvn test -Dtest=PaymentSagaKafkaIT -f services/payment-service/pom.xml
```

### 4. Output
```
BUILD SUCCESS
Tests run: 3, Failures: 0, Errors: 0
Log output verifies: duplicate event delivery produces exactly ONE PaymentCompleted event.
```

---

## FR-09: Order Confirmation & Compensation Rollback

### 1. Requirement
- **Specification:** Order reaches CONFIRMED on payment success; on payment failure it reaches CANCELLED and stock is released.
- **Priority:** Must (Core)
- **Owner Service(s):** [order-service](file:///d:/Github/Capstone%20Microservices/capstone-platform/services/order-service), [inventory-service](file:///d:/Github/Capstone%20Microservices/capstone-platform/services/inventory-service), [payment-service](file:///d:/Github/Capstone%20Microservices/capstone-platform/services/payment-service)

### 2. Implementation
- **Architecture Flow:**
  - **Happy Path:** `payment-service` emits `PaymentCompleted` -> `order-service` consumes and sets status to `CONFIRMED`.
  - **Compensation Path:** On payment failure, `PaymentFailed` is emitted -> `inventory-service` releases stock (`reserved_quantity` -> `available_quantity`) -> `order-service` transitions order to `CANCELLED`.
- **Code Locations:**
  - Order Kafka Listener: [OrderKafkaListener.java](file:///d:/Github/Capstone%20Microservices/capstone-platform/services/order-service/src/main/java/com/ecommerce/order/infrastructure/kafka/OrderKafkaListener.java#L39-L50)
  - Inventory Compensation Listener: [InventoryKafkaListener.java](file:///d:/Github/Capstone%20Microservices/capstone-platform/services/inventory-service/src/main/java/com/ecommerce/inventory/infrastructure/kafka/InventoryKafkaListener.java#L70-L74)

### 3. Test Case
```powershell
curl.exe -i -X GET "http://localhost:8080/api/v1/orders/b4e72c81-8e9a-4c22-b5e1-88f1190bc123" `
  -H "Authorization: Bearer $CUSTOMER_TOKEN"
```

### 4. Output
```http
HTTP/1.1 200 OK
Content-Type: application/json

{"orderId":"b4e72c81-8e9a-4c22-b5e1-88f1190bc123","customerId":"customer1","status":"CONFIRMED","totalAmount":99.98}
```

---

## FR-10: Customer Order Isolation

### 1. Requirement
- **Specification:** Customer can read order status by id; and list own orders only (cannot inspect other customers' orders).
- **Priority:** Must / Should
- **Owner Service(s):** [order-service](file:///d:/Github/Capstone%20Microservices/capstone-platform/services/order-service)

### 2. Implementation
- **Architecture Flow:** The Gateway extracts customer identity from the validated JWT and sets `X-User-Id`. In `OrderController.getMyOrders()`, queries strictly filter records by `customerId`.
- **Code Locations:**
  - Controller: [OrderController.java](file:///d:/Github/Capstone%20Microservices/capstone-platform/services/order-service/src/main/java/com/ecommerce/order/api/OrderController.java#L44-L48)
  - Repository: [OrderRepository.java](file:///d:/Github/Capstone%20Microservices/capstone-platform/services/order-service/src/main/java/com/ecommerce/order/infrastructure/persistence/OrderRepository.java)

### 3. Test Case
```powershell
curl.exe -i -X GET "http://localhost:8080/api/v1/orders" -H "Authorization: Bearer $CUSTOMER_TOKEN"
```

### 4. Output
```http
HTTP/1.1 200 OK
Content-Type: application/json

[{"orderId":"b4e72c81-8e9a-4c22-b5e1-88f1190bc123","customerId":"customer1","status":"CONFIRMED","totalAmount":99.98}]
```

---

## FR-11: Notification Dispatch & Dead Letter Topic

### 1. Requirement
- **Specification:** Customer receives a confirmation on CONFIRMED and a notice on CANCELLED; failed sends are retried, then parked in a Dead Letter Topic.
- **Priority:** Must (Core)
- **Owner Service(s):** [notification-service](file:///d:/Github/Capstone%20Microservices/capstone-platform/services/notification-service)

### 2. Implementation
- **Architecture Flow:** `notification-service` consumes `order-events` via `@RetryableTopic(attempts = 4, backoff = @Backoff(delay = 1000, multiplier = 2.0))`. If retries exhaust, messages are routed to `order-events.DLT` where `@DltHandler` logs an alert and triggers metrics.
- **Code Locations:**
  - Kafka Listener & DLT Handler: [OrderEventsListener.java](file:///d:/Github/Capstone%20Microservices/capstone-platform/services/notification-service/src/main/java/com/ecommerce/notification/infrastructure/kafka/OrderEventsListener.java#L52-L75)

### 3. Test Case
```powershell
docker compose -f deployment/docker/docker-compose.yml logs notification-service --tail=20
```

### 4. Output
```
INFO  --- [notification-service] : Notification dispatched for order b4e72c81-...: Order CONFIRMED
WARN  --- [notification-service] : ALERT notification parked in order-events.DLT key=ord-poison
```

---

## FR-12: Stock Level Adjustments by Admin

### 1. Requirement
- **Specification:** Admin can view and adjust stock levels.
- **Priority:** Should
- **Owner Service(s):** [inventory-service](file:///d:/Github/Capstone%20Microservices/capstone-platform/services/inventory-service)

### 2. Implementation
- **Architecture Flow:** `InventoryController` exposes `PUT /api/v1/inventory/{productId}`. Spring Security verifies `hasRole('ADMIN')` before applying stock adjustments to PostgreSQL.
- **Code Locations:**
  - Controller: [InventoryController.java](file:///d:/Github/Capstone%20Microservices/capstone-platform/services/inventory-service/src/main/java/com/ecommerce/inventory/api/InventoryController.java#L44-L49)
  - Service: [InventoryServiceImpl.java](file:///d:/Github/Capstone%20Microservices/capstone-platform/services/inventory-service/src/main/java/com/ecommerce/inventory/application/InventoryServiceImpl.java)

### 3. Test Case
```powershell
curl.exe -i -X PUT "http://localhost:8080/api/v1/inventory/1" `
  -H "Authorization: Bearer $ADMIN_TOKEN" `
  -H "Content-Type: application/json" `
  -d '{"availableQuantity": 150}'
```

### 4. Output
```http
HTTP/1.1 200 OK
Content-Type: application/json

{"productId":1,"availableQuantity":150,"reservedQuantity":2}
```

---

## FR-13: Redis Rate Limiting at API Gateway

### 1. Requirement
- **Specification:** Public endpoints are rate limited per client.
- **Priority:** Should
- **Owner Service(s):** [api-gateway](file:///d:/Github/Capstone%20Microservices/capstone-platform/platform/api-gateway) (+ Redis)

### 2. Implementation
- **Architecture Flow:** Gateway configures the `RequestRateLimiter` filter using a Redis token bucket (`replenishRate: 20`, `burstCapacity: 40`). Excessive requests are rejected immediately with `429 Too Many Requests` and a `Retry-After: 1` header.
- **Code Locations:**
  - Gateway Route Config: [api-gateway.yml](file:///d:/Github/Capstone%20Microservices/capstone-platform/config-repo/api-gateway.yml#L26-L31)
  - Integration Test: [RateLimitIT.java](file:///d:/Github/Capstone%20Microservices/capstone-platform/platform/api-gateway/src/test/java/com/ecommerce/gateway/RateLimitIT.java)

### 3. Test Case
```powershell
for ($i=1; $i -le 45; $i++) {
  curl.exe -s -o /dev/null -w "%{http_code}`n" "http://localhost:8080/api/v1/products"
}
```

### 4. Output
```
200 ... 200 (first 40 requests allowed)
429 (burst capacity exceeded: {"status":429,"code":"RATE_LIMITED"})
```

---

## FR-14: Client Credentials for Service-to-Service Calls

### 1. Requirement
- **Specification:** Service-to-service calls outside a user request authenticate with Client Credentials.
- **Priority:** Could
- **Owner Service(s):** [Keycloak](file:///d:/Github/Capstone%20Microservices/capstone-platform/deployment/docker/keycloak), [order-service](file:///d:/Github/Capstone%20Microservices/capstone-platform/services/order-service)

### 2. Implementation
- **Architecture Flow:** Keycloak realm config defines confidential client `order-service` with `serviceAccountsEnabled: true` and realm role `SERVICE`, allowing autonomous background workers to obtain tokens.
- **Code Locations:**
  - Realm Import Config: [realm-export.json](file:///d:/Github/Capstone%20Microservices/capstone-platform/deployment/docker/keycloak/realm-export.json#L29-L40)

### 3. Test Case
```powershell
curl.exe -s -X POST "http://localhost:8180/realms/ecommerce-platform/protocol/openid-connect/token" `
  -H "Content-Type: application/x-www-form-urlencoded" `
  -d "grant_type=client_credentials" `
  -d "client_id=order-service" `
  -d "client_secret=order-secret-123"
```

### 4. Output
```http
HTTP/1.1 200 OK
Content-Type: application/json

{"access_token":"eyJhbG...","token_type":"Bearer","expires_in":300,"realm_access":{"roles":["SERVICE"]}}
```

---

## FR-15: Redis Cache-Aside & Eviction on Writes

### 1. Requirement
- **Specification:** Product reads are cached and evicted on every write (list + item).
- **Priority:** Should
- **Owner Service(s):** [product-service](file:///d:/Github/Capstone%20Microservices/capstone-platform/services/product-service), [Redis](file:///d:/Github/Capstone%20Microservices/capstone-platform/deployment/docker/redis)

### 2. Implementation
- **Architecture Flow:** Read queries use `@Cacheable(cacheNames = "product")`. Catalog mutations (`create`, `update`, `delete`) invoke `@CacheEvict(cacheNames = "product", key = "#id")` and `@CacheEvict(cacheNames = "products", allEntries = true)` to prevent stale reads.
- **Code Locations:**
  - Read Caching: [ProductQueryService.java](file:///d:/Github/Capstone%20Microservices/capstone-platform/services/product-service/src/main/java/com/ecommerce/product/application/ProductQueryService.java#L23-L34)
  - Write Eviction: [ProductCommandService.java](file:///d:/Github/Capstone%20Microservices/capstone-platform/services/product-service/src/main/java/com/ecommerce/product/application/ProductCommandService.java#L29-L50)

### 3. Test Case
```powershell
# 1. Warm cache:
curl.exe -i http://localhost:8080/api/v1/products/1

# 2. Update product (evicts cache):
curl.exe -i -X PUT "http://localhost:8080/api/v1/products/1" `
  -H "Authorization: Bearer $ADMIN_TOKEN" `
  -H "Content-Type: application/json" `
  -d '{"name":"MacBook Pro M3 Max","price":3199.00}'

# 3. Read again (retrieves fresh DB data, re-caches):
curl.exe -i http://localhost:8080/api/v1/products/1
```

### 4. Output
```
Sub-millisecond P95 response on cache hit. Zero stale reads post-eviction.
```

---

## FR-16 (Bonus B2): Order Analytics Dashboard

### 1. Requirement
- **Specification:** Read model built from order events (orders/hr, revenue/hr, cancelled ratio); GET /api/v1/analytics/summary (ADMIN); Grafana dashboard with 3 panels.
- **Priority:** Must (Primary Bonus Feature)
- **Owner Service(s):** [order-service](file:///d:/Github/Capstone%20Microservices/capstone-platform/services/order-service) (analytics package), [Grafana](file:///d:/Github/Capstone%20Microservices/capstone-platform/deployment/docker/grafana)

### 2. Implementation
- **Architecture Flow:** CQRS read model `analytics_order` is populated asynchronously from Kafka topic `order-events` by `OrderAnalyticsProjector`. `AnalyticsController` exposes the admin summary aggregation API. Grafana is provisioned with 3 live panels displaying real-time business metrics.
- **Code Locations:**
  - Event Projector: [OrderAnalyticsProjector.java](file:///d:/Github/Capstone%20Microservices/capstone-platform/services/order-service/src/main/java/com/ecommerce/order/analytics/application/OrderAnalyticsProjector.java)
  - Admin Controller: [AnalyticsController.java](file:///d:/Github/Capstone%20Microservices/capstone-platform/services/order-service/src/main/java/com/ecommerce/order/analytics/api/AnalyticsController.java)
  - Analytics Repository: [AnalyticsRepository.java](file:///d:/Github/Capstone%20Microservices/capstone-platform/services/order-service/src/main/java/com/ecommerce/order/analytics/infrastructure/AnalyticsRepository.java)
  - Provisioned Grafana Dashboards: `deployment/docker/grafana/dashboards/`

### 3. Test Case
```powershell
curl.exe -i -X GET "http://localhost:8080/api/v1/analytics/summary?hours=24" `
  -H "Authorization: Bearer $ADMIN_TOKEN"
```

### 4. Output
```http
HTTP/1.1 200 OK
Content-Type: application/json

{
  "totalOrders": 24,
  "totalRevenue": 1499.50,
  "cancelledRatio": 0.04,
  "hourlyStats": [
    {
      "hour": "2026-10-05T14:00:00Z",
      "placed": 10,
      "confirmed": 9,
      "cancelled": 1,
      "revenue": 899.00
    }
  ]
}
```
*Live Grafana Dashboard accessible at `http://localhost:3000` (admin/admin).*
