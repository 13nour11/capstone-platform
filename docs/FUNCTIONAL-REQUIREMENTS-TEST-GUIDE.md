# Capstone Microservices: Functional Requirements Test & Architecture Guide

> **Prepared for:** Final Capstone Presentation & Defense (Dr. ElSayed Baladoh)  
> **Repository:** https://github.com/13nour11/capstone-platform  
> **Status:** 100% Green across all 9 Maven Modules (`BUILD SUCCESS`, 0 errors, 0 failures)

---

## Quick Reference: JWT Token Setup for Testing

Before running the test cases below, obtain the `CUSTOMER` and `ADMIN` tokens from Keycloak (`http://localhost:8180`):

### In PowerShell:
```powershell
# Get Customer Token
$CUSTOMER_TOKEN = (curl.exe -s -X POST "http://localhost:8180/realms/ecommerce-platform/protocol/openid-connect/token" `
  -H "Content-Type: application/x-www-form-urlencoded" `
  -d "grant_type=password" `
  -d "client_id=api-gateway" `
  -d "username=customer1" `
  -d "password=password" | ConvertFrom-Json).access_token

# Get Admin Token
$ADMIN_TOKEN = (curl.exe -s -X POST "http://localhost:8180/realms/ecommerce-platform/protocol/openid-connect/token" `
  -H "Content-Type: application/x-www-form-urlencoded" `
  -d "grant_type=password" `
  -d "client_id=api-gateway" `
  -d "username=admin" `
  -d "password=password" | ConvertFrom-Json).access_token
```

---

## FR-01: Public Product Catalog Browsing
- **Priority:** Must (Core)
- **Owner Service(s):** [product-service](file:///d:/Github/Capstone%20Microservices/capstone-platform/services/product-service), [api-gateway](file:///d:/Github/Capstone%20Microservices/capstone-platform/platform/api-gateway)
- **How It Works:**
  Anyone can browse products with pagination, sorting, and category filters without passing an Authorization token. The API Gateway explicitly permits all `GET /api/v1/products/**` calls. Reads check the Redis cache-aside first; on a cache miss, the JPA projection query executes and stores the result in Redis.
- **Code Locations:**
  - Controller: [ProductController.java](file:///d:/Github/Capstone%20Microservices/capstone-platform/services/product-service/src/main/java/com/ecommerce/product/api/ProductController.java#L43-L48)
  - Security Config: [SecurityConfig.java](file:///d:/Github/Capstone%20Microservices/capstone-platform/platform/api-gateway/src/main/java/com/ecommerce/gateway/security/SecurityConfig.java#L40) (`.pathMatchers(HttpMethod.GET, "/api/v1/products/**").permitAll()`)
- **How to Test:**
```powershell
curl.exe -i -X GET "http://localhost:8080/api/v1/products?page=0&size=10"
```
- **Expected Output:**
  - Status: `HTTP/1.1 200 OK`
  - Body: JSON object with paginated items (`content: [...]`, `pageNumber: 0`, `totalElements: ...`).

---

## FR-02: Admin Product Management
- **Priority:** Must (Core)
- **Owner Service(s):** [product-service](file:///d:/Github/Capstone%20Microservices/capstone-platform/services/product-service)
- **How It Works:**
  `POST`, `PUT`, and `DELETE` requests require a valid JWT with the `ADMIN` role. The Gateway converts Keycloak realm roles (`roles: ["ADMIN"]`) into Spring Security authorities (`ROLE_ADMIN`). `@PreAuthorize("hasRole('ADMIN')")` protects write endpoints.
- **Code Locations:**
  - Controller: [ProductController.java](file:///d:/Github/Capstone%20Microservices/capstone-platform/services/product-service/src/main/java/com/ecommerce/product/api/ProductController.java#L55-L73)
  - Gateway Role Converter: [KeycloakRealmRoleConverter.java](file:///d:/Github/Capstone%20Microservices/capstone-platform/platform/api-gateway/src/main/java/com/ecommerce/gateway/security/KeycloakRealmRoleConverter.java)
- **How to Test:**
  1. *Without Token (Unauthorized):*
  ```powershell
  curl.exe -i -X POST "http://localhost:8080/api/v1/products" -H "Content-Type: application/json" -d "{}"
  ```
  Expected: `401 Unauthorized`
  2. *With Customer Token (Forbidden):*
  ```powershell
  curl.exe -i -X POST "http://localhost:8080/api/v1/products" -H "Authorization: Bearer $CUSTOMER_TOKEN" -H "Content-Type: application/json" -d "{}"
  ```
  Expected: `403 Forbidden`
  3. *With Admin Token (Success):*
  ```powershell
  curl.exe -i -X POST "http://localhost:8080/api/v1/products" `
    -H "Authorization: Bearer $ADMIN_TOKEN" `
    -H "Content-Type: application/json" `
    -d '{"sku":"PROD-IPHONE-15","name":"iPhone 15 Pro","description":"Titanium 256GB","price":1199.00,"categoryId":1}'
  ```
  Expected: `201 Created` with generated `id` and `Location` header.

---

## FR-03: Category Name in Product Detail (Read Model)
- **Priority:** Should
- **Owner Service(s):** [product-service](file:///d:/Github/Capstone%20Microservices/capstone-platform/services/product-service)
- **How It Works:**
  Instead of returning raw database entities with separate joins, `ProductRepository` uses a Spring Data JPA projection (`ProductView`) that projects `category.name` directly into the read model (`ProductDetails`).
- **Code Locations:**
  - Read Model DTO: [ProductDetails.java](file:///d:/Github/Capstone%20Microservices/capstone-platform/services/product-service/src/main/java/com/ecommerce/product/application/ProductDetails.java#L9-L15) (`categoryName` field)
  - Projection Interface: [ProductView.java](file:///d:/Github/Capstone%20Microservices/capstone-platform/services/product-service/src/main/java/com/ecommerce/product/domain/ProductView.java)
- **How to Test:**
```powershell
curl.exe -i -X GET "http://localhost:8080/api/v1/products/1"
```
- **Expected Output:**
  JSON payload contains `"categoryId": 1` AND `"categoryName": "Electronics"`.

---

## FR-04: Keycloak Authentication & Protected Routes
- **Priority:** Must (Core)
- **Owner Service(s):** [api-gateway](file:///d:/Github/Capstone%20Microservices/capstone-platform/platform/api-gateway), [Keycloak](file:///d:/Github/Capstone%20Microservices/capstone-platform/deployment/docker/keycloak)
- **How It Works:**
  Keycloak runs as the identity provider (realm: `ecommerce-platform`). The API Gateway serves as the trust boundary (OAuth2 Resource Server). Protected routes require a valid Bearer token.
- **Code Locations:**
  - Security Filter Chain: [SecurityConfig.java](file:///d:/Github/Capstone%20Microservices/capstone-platform/platform/api-gateway/src/main/java/com/ecommerce/gateway/security/SecurityConfig.java#L26-L56)
  - Realm Import Config: [realm-export.json](file:///d:/Github/Capstone%20Microservices/capstone-platform/deployment/docker/keycloak/realm-export.json)
- **How to Test:**
```powershell
# Attempt to access protected order endpoint with no token
curl.exe -i http://localhost:8080/api/v1/orders
```
- **Expected Output:**
  - `HTTP/1.1 401 Unauthorized`
  - Header: `WWW-Authenticate: Bearer`
  - Body: RFC 7807 problem JSON (`code: "UNAUTHORIZED"`).

---

## FR-05: Authenticated Order Placement
- **Priority:** Must (Core)
- **Owner Service(s):** [order-service](file:///d:/Github/Capstone%20Microservices/capstone-platform/services/order-service)
- **How It Works:**
  Authenticated customer sends `POST /api/v1/orders`. The order is saved with status `PENDING`, an `OrderPlaced` event is inserted into the `outbox_event` table, and the API responds immediately with `201 Created` without waiting for payment or saga completion.
- **Code Locations:**
  - Controller: [OrderController.java](file:///d:/Github/Capstone%20Microservices/capstone-platform/services/order-service/src/main/java/com/ecommerce/order/api/OrderController.java#L31-L37)
  - Service: [OrderPersistenceService.java](file:///d:/Github/Capstone%20Microservices/capstone-platform/services/order-service/src/main/java/com/ecommerce/order/application/OrderPersistenceService.java#L61-L107)
- **How to Test:**
```powershell
curl.exe -i -X POST "http://localhost:8080/api/v1/orders" `
  -H "Authorization: Bearer $CUSTOMER_TOKEN" `
  -H "Content-Type: application/json" `
  -d '{"customerId":"customer1","items":[{"productId":1,"quantity":2,"unitPrice":29.99}]}'
```
- **Expected Output:**
  - `HTTP/1.1 201 Created`
  - Body: `{"orderId": "uuid...", "status": "PENDING", "totalAmount": 59.98}`.

---

## FR-06: Synchronous Stock Pre-Check (Zero Network I/O in DB Tx)
- **Priority:** Must (Core)
- **Owner Service(s):** [order-service](file:///d:/Github/Capstone%20Microservices/capstone-platform/services/order-service), [inventory-service](file:///d:/Github/Capstone%20Microservices/capstone-platform/services/inventory-service)
- **How It Works:**
  Before opening a database transaction, `order-service` calls `inventory-service` synchronously via OpenFeign (`InventoryServiceClient`) wrapped in Resilience4j circuit breaker and retry. If any item is out of stock, the order is rejected immediately (fail-fast), without touching payment or opening a database transaction.
- **Code Locations:**
  - Pre-Check Execution: [OrderServiceImpl.java](file:///d:/Github/Capstone%20Microservices/capstone-platform/services/order-service/src/main/java/com/ecommerce/order/application/OrderServiceImpl.java#L48-L52)
  - Feign Client: [InventoryServiceClient.java](file:///d:/Github/Capstone%20Microservices/capstone-platform/services/order-service/src/main/java/com/ecommerce/order/infrastructure/client/InventoryServiceClient.java)
  - Stock Check Endpoint: [InventoryController.java](file:///d:/Github/Capstone%20Microservices/capstone-platform/services/inventory-service/src/main/java/com/ecommerce/inventory/api/InventoryController.java#L31-L36)
- **How to Test:**
```powershell
# Attempt to order an impossible quantity (e.g., 9999 units)
curl.exe -i -X POST "http://localhost:8080/api/v1/orders" `
  -H "Authorization: Bearer $CUSTOMER_TOKEN" `
  -H "Content-Type: application/json" `
  -d '{"customerId":"customer1","items":[{"productId":1,"quantity":9999,"unitPrice":29.99}]}'
```
- **Expected Output:**
  - `HTTP/1.1 422 Unprocessable Entity` or `400 Bad Request`
  - Message: `Product 1 is out of stock`. No order or outbox record is persisted.

---

## FR-07: Stock Reservation & Atomic Deductions
- **Priority:** Must (Core)
- **Owner Service(s):** [inventory-service](file:///d:/Github/Capstone%20Microservices/capstone-platform/services/inventory-service)
- **How It Works:**
  `inventory-service` consumes `OrderPlaced` from Kafka topic `order-events`. It acquires a pessimistic write lock (`PESSIMISTIC_WRITE`) on the stock row in PostgreSQL, decreases `available_quantity`, increases `reserved_quantity`, and emits `InventoryReserved` through its transactional outbox.
- **Code Locations:**
  - Listener: [InventoryKafkaListener.java](file:///d:/Github/Capstone%20Microservices/capstone-platform/services/inventory-service/src/main/java/com/ecommerce/inventory/infrastructure/kafka/InventoryKafkaListener.java#L43-L46)
  - Service Logic: [InventoryServiceImpl.java](file:///d:/Github/Capstone%20Microservices/capstone-platform/services/inventory-service/src/main/java/com/ecommerce/inventory/application/InventoryServiceImpl.java#L115-L144)
  - Atomic Query: [StockRepository.java](file:///d:/Github/Capstone%20Microservices/capstone-platform/services/inventory-service/src/main/java/com/ecommerce/inventory/infrastructure/persistence/StockRepository.java)
- **How to Test:**
```powershell
# Check stock before and after an order of 2 units
curl.exe -i -X GET "http://localhost:8080/api/v1/inventory/1" -H "Authorization: Bearer $ADMIN_TOKEN"
```
- **Expected Output:**
  `availableQuantity` is decremented by 2, and `reservedQuantity` is incremented by 2.

---

## FR-08: Exactly-Once Idempotent Payment
- **Priority:** Must (Core)
- **Owner Service(s):** [payment-service](file:///d:/Github/Capstone%20Microservices/capstone-platform/services/payment-service)
- **How It Works:**
  `payment-service` consumes `InventoryReserved` from `inventory-events`. It uses `orderId` as the unique idempotency key. A unique database constraint on `processed_event(event_id, consumer_group)` prevents duplicate charges. If the message is re-delivered by Kafka, `DataIntegrityViolationException` is caught and acknowledged without double-charging.
- **Code Locations:**
  - Listener: [InventoryEventsListener.java](file:///d:/Github/Capstone%20Microservices/capstone-platform/services/payment-service/src/main/java/com/ecommerce/payment/infrastructure/messaging/InventoryEventsListener.java)
  - Handler: [HandleInventoryReservedService.java](file:///d:/Github/Capstone%20Microservices/capstone-platform/services/payment-service/src/main/java/com/ecommerce/payment/application/HandleInventoryReservedService.java#L43-L58)
  - Repository: [ProcessedEventRepository.java](file:///d:/Github/Capstone%20Microservices/capstone-platform/services/payment-service/src/main/java/com/ecommerce/payment/infrastructure/persistence/ProcessedEventRepository.java)
- **How to Test:**
  Verified automatically by `PaymentSagaKafkaIT`:
  ```bash
  mvn test -Dtest=PaymentSagaKafkaIT -f services/payment-service/pom.xml
  ```
  Tests that sending duplicate `InventoryReserved` events emits exactly ONE `PaymentCompleted` event.

---

## FR-09: Order Confirmation & Compensation Rollback
- **Priority:** Must (Core)
- **Owner Service(s):** [order-service](file:///d:/Github/Capstone%20Microservices/capstone-platform/services/order-service), [inventory-service](file:///d:/Github/Capstone%20Microservices/capstone-platform/services/inventory-service), [payment-service](file:///d:/Github/Capstone%20Microservices/capstone-platform/services/payment-service)
- **How It Works:**
  - **Happy Path:** `payment-service` publishes `PaymentCompleted` -> `order-service` updates status from `PENDING` to `CONFIRMED`.
  - **Failure Path:** If payment is declined, `payment-service` publishes `PaymentFailed` -> `inventory-service` consumes it and releases reserved stock -> `order-service` consumes it and transitions order to `CANCELLED`.
- **Code Locations:**
  - Order Listener: [OrderKafkaListener.java](file:///d:/Github/Capstone%20Microservices/capstone-platform/services/order-service/src/main/java/com/ecommerce/order/infrastructure/kafka/OrderKafkaListener.java#L39-L50)
  - Inventory Compensation: [InventoryKafkaListener.java](file:///d:/Github/Capstone%20Microservices/capstone-platform/services/inventory-service/src/main/java/com/ecommerce/inventory/infrastructure/kafka/InventoryKafkaListener.java#L70-L74)
- **How to Test:**
```powershell
# Check status of order
curl.exe -i -X GET "http://localhost:8080/api/v1/orders/<orderId>" `
  -H "Authorization: Bearer $CUSTOMER_TOKEN"
```
- **Expected Output:**
  - Happy Path: `"status": "CONFIRMED"`
  - Compensation: `"status": "CANCELLED"`

---

## FR-10: Customer Order Isolation
- **Priority:** Must / Should
- **Owner Service(s):** [order-service](file:///d:/Github/Capstone%20Microservices/capstone-platform/services/order-service)
- **How It Works:**
  Gateway extracts user identity from JWT and sets `X-User-Id` header. `OrderController.getMyOrders()` filters database queries strictly by `customerId`. Customers can only view their own orders.
- **Code Locations:**
  - Controller: [OrderController.java](file:///d:/Github/Capstone%20Microservices/capstone-platform/services/order-service/src/main/java/com/ecommerce/order/api/OrderController.java#L44-L48)
- **How to Test:**
```powershell
# Customer 1 retrieves their orders
curl.exe -i -X GET "http://localhost:8080/api/v1/orders" `
  -H "Authorization: Bearer $CUSTOMER_TOKEN"
```
- **Expected Output:**
  Only orders belonging to `customer1` are returned in the JSON list.

---

## FR-11: Notification Dispatch & Dead Letter Topic
- **Priority:** Must (Core)
- **Owner Service(s):** [notification-service](file:///d:/Github/Capstone%20Microservices/capstone-platform/services/notification-service)
- **How It Works:**
  `notification-service` consumes `order-events`. When `OrderConfirmed` or `OrderCancelled` arrives, it sends a notification log. If an unexpected exception occurs, `@RetryableTopic` retries 4 times with exponential backoff (1s, 2s, 4s), and if exhausted, routes the message to `order-events.DLT` where `@DltHandler` logs an alert and increments the metric.
- **Code Locations:**
  - Listener: [OrderEventsListener.java](file:///d:/Github/Capstone%20Microservices/capstone-platform/services/notification-service/src/main/java/com/ecommerce/notification/infrastructure/kafka/OrderEventsListener.java#L52-L75)
- **How to Test:**
  Check notification service logs:
```powershell
docker compose -f deployment/docker/docker-compose.yml logs notification-service --tail=20
```

---

## FR-12: Stock Level Adjustments by Admin
- **Priority:** Should
- **Owner Service(s):** [inventory-service](file:///d:/Github/Capstone%20Microservices/capstone-platform/services/inventory-service)
- **How It Works:**
  Allows administrators to update available stock for an existing product.
- **Code Locations:**
  - Controller: [InventoryController.java](file:///d:/Github/Capstone%20Microservices/capstone-platform/services/inventory-service/src/main/java/com/ecommerce/inventory/api/InventoryController.java#L44-L49)
- **How to Test:**
```powershell
curl.exe -i -X PUT "http://localhost:8080/api/v1/inventory/1" `
  -H "Authorization: Bearer $ADMIN_TOKEN" `
  -H "Content-Type: application/json" `
  -d '{"availableQuantity": 150}'
```
- **Expected Output:**
  `200 OK` with `"availableQuantity": 150`.

---

## FR-13: Redis Rate Limiter at API Gateway
- **Priority:** Should
- **Owner Service(s):** [api-gateway](file:///d:/Github/Capstone%20Microservices/capstone-platform/platform/api-gateway)
- **How It Works:**
  `api-gateway` enforces Redis token-bucket rate limiting via `RequestRateLimiter` (`replenishRate: 20`, `burstCapacity: 40`). Once capacity is exceeded, it returns `429 Too Many Requests` with `Retry-After: 1`.
- **Code Locations:**
  - Configuration: [api-gateway.yml](file:///d:/Github/Capstone%20Microservices/capstone-platform/config-repo/api-gateway.yml#L26-L31)
  - Integration Test: [RateLimitIT.java](file:///d:/Github/Capstone%20Microservices/capstone-platform/platform/api-gateway/src/test/java/com/ecommerce/gateway/RateLimitIT.java)
- **How to Test:**
```powershell
# Send 45 rapid requests in a loop
for ($i=1; $i -le 45; $i++) {
  curl.exe -s -o /dev/null -w "%{http_code}`n" "http://localhost:8080/api/v1/products"
}
```
- **Expected Output:**
  First requests return `200`, followed by `429` with `application/problem+json`.

---

## FR-14: Client Credentials for Service-to-Service Calls
- **Priority:** Could
- **Owner Service(s):** [Keycloak](file:///d:/Github/Capstone%20Microservices/capstone-platform/deployment/docker/keycloak), [order-service](file:///d:/Github/Capstone%20Microservices/capstone-platform/services/order-service)
- **How It Works:**
  Keycloak realm provides confidential client `order-service` with `serviceAccountsEnabled: true`. Calls outside of an active customer session authenticate using `client_credentials`.
- **Code Locations:**
  - Realm Config: [realm-export.json](file:///d:/Github/Capstone%20Microservices/capstone-platform/deployment/docker/keycloak/realm-export.json#L29-L40)
- **How to Test:**
```powershell
curl.exe -s -X POST "http://localhost:8180/realms/ecommerce-platform/protocol/openid-connect/token" `
  -H "Content-Type: application/x-www-form-urlencoded" `
  -d "grant_type=client_credentials" `
  -d "client_id=order-service" `
  -d "client_secret=order-secret-123"
```
- **Expected Output:**
  Returns JWT token with `"realm_access": {"roles": ["SERVICE"]}`.

---

## FR-15: Redis Cache-Aside & Eviction on Writes
- **Priority:** Should
- **Owner Service(s):** [product-service](file:///d:/Github/Capstone%20Microservices/capstone-platform/services/product-service)
- **How It Works:**
  Product queries check Redis before querying PostgreSQL. Any product write (`create`, `update`, `delete`) invokes `@CacheEvict(cacheNames = "product", key = "#id")` and `@CacheEvict(cacheNames = "products", allEntries = true)`.
- **Code Locations:**
  - Read Caching: [ProductQueryService.java](file:///d:/Github/Capstone%20Microservices/capstone-platform/services/product-service/src/main/java/com/ecommerce/product/application/ProductQueryService.java#L23-L34)
  - Write Eviction: [ProductCommandService.java](file:///d:/Github/Capstone%20Microservices/capstone-platform/services/product-service/src/main/java/com/ecommerce/product/application/ProductCommandService.java#L29-L50)
- **How to Test:**
  1. Call `GET /api/v1/products/1` -> Cache is populated in Redis.
  2. Call `PUT /api/v1/products/1` with new name -> Cache entry is evicted.
  3. Call `GET /api/v1/products/1` -> Immediate fresh data returned without stale read.

---

## FR-16 (Bonus B2): Order Analytics Dashboard
- **Priority:** Must (Primary Bonus Feature)
- **Owner Service(s):** [order-service](file:///d:/Github/Capstone%20Microservices/capstone-platform/services/order-service) (analytics package) + [Grafana](file:///d:/Github/Capstone%20Microservices/capstone-platform/deployment/docker/grafana)
- **How It Works:**
  CQRS architecture. A dedicated Kafka listener (`OrderEventsAnalyticsListener`) consumes `order-events` and updates an analytics read model (`analytics_order`). Admin endpoint `GET /api/v1/analytics/summary` aggregates orders/hour, revenue, and cancelled ratio. Grafana dashboard is pre-provisioned with 3 live panels.
- **Code Locations:**
  - Projector: [OrderAnalyticsProjector.java](file:///d:/Github/Capstone%20Microservices/capstone-platform/services/order-service/src/main/java/com/ecommerce/order/analytics/application/OrderAnalyticsProjector.java)
  - Controller: [AnalyticsController.java](file:///d:/Github/Capstone%20Microservices/capstone-platform/services/order-service/src/main/java/com/ecommerce/order/analytics/api/AnalyticsController.java)
  - Repository: [AnalyticsRepository.java](file:///d:/Github/Capstone%20Microservices/capstone-platform/services/order-service/src/main/java/com/ecommerce/order/analytics/infrastructure/AnalyticsRepository.java)
  - Grafana Dashboard: `deployment/docker/grafana/dashboards/`
- **How to Test:**
```powershell
curl.exe -i -X GET "http://localhost:8080/api/v1/analytics/summary?hours=24" `
  -H "Authorization: Bearer $ADMIN_TOKEN"
```
- **Expected Output:**
```json
{
  "totalOrders": 24,
  "totalRevenue": 1499.50,
  "cancelledRatio": 0.05,
  "hourlyStats": [
    {"hour": "2026-10-05T14:00:00Z", "placed": 10, "confirmed": 9, "cancelled": 1, "revenue": 899.00}
  ]
}
```
Open Grafana in browser: `http://localhost:3000` (admin/admin) to view the provisioned live dashboard.
