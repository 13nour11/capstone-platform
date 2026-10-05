# Architecture diagrams

Digital versions of the three views the Brief asks for (§10.3), kept in sync with the code. The **paper drawings**
themselves must be drawn and photographed by the team and added here (`01-…jpg`, `02-…jpg`, `03-…jpg`).
`01-bonus-boundaries.svg` is Member C's drawing of the B2 boundaries.

## 1. Service boundaries (Core + Bonuses)

```mermaid
flowchart LR
    client([Customer / Admin<br/>browser, curl, k6]) -->|HTTPS + Bearer JWT| gw[api-gateway :8080<br/>JWT, roles, rate limit,<br/>X-User-*, X-Tenant-Id]
    kc[(Keycloak :8180)] -. JWKS .-> gw
    gw --> product[product :8081<br/>catalogue, ratings B1,<br/>tenants B3]
    gw --> order[order :8082<br/>orders, analytics B2]
    gw --> inventory[inventory :8084<br/>stock, low-stock B4]
    gw --> payment[payment :8083]
    gw --> review[review :8086<br/>B1]
    gw -->|SSE| notification[notification :8085<br/>notices, alerts B4]
    order -->|Feign + Resilience4j<br/>client credentials| inventory
    product --- redis[(Redis)]
    gw --- redis
    subgraph kafka [Kafka]
      oe[[order-events]]
      ie[[inventory-events]]
      pe[[payment-events]]
      ia[[inventory-alerts]]
      re[[review-events]]
    end
    order --> oe --> inventory & notification & order
    inventory --> ie --> payment & order
    payment --> pe --> order & inventory
    inventory --> ia --> notification
    review --> re --> product
```

## 2. Place-order flow: happy path and one failure path

```mermaid
sequenceDiagram
    autonumber
    participant C as Customer
    participant G as Gateway
    participant O as order
    participant I as inventory
    participant P as payment
    participant N as notification
    C->>G: POST /api/v1/orders (JWT)
    G->>O: + X-User-Id
    O->>I: GET /check (Feign, CB/Retry/Bulkhead/TimeLimiter, SERVICE token)
    I-->>O: available=true
    O->>O: save PENDING + outbox OrderPlaced (one transaction)
    O-->>C: 201 {orderId, PENDING}
    O--)I: OrderPlaced
    I->>I: reserve each line + outbox InventoryReserved
    I--)P: InventoryReserved
    alt payment succeeds
        P--)O: PaymentCompleted
        O->>O: CONFIRMED + outbox OrderConfirmed
        O--)N: OrderConfirmed → confirmation
        P--)I: PaymentCompleted → reservation CONSUMED
    else payment fails (compensation)
        P--)O: PaymentFailed
        O->>O: CANCELLED + outbox OrderCancelled
        O--)N: OrderCancelled → cancel notice
        P--)I: PaymentFailed → release stock, InventoryReleased
    end
    C->>G: GET /api/v1/orders/{id}
```

## 3. Where data lives (table → database)

```mermaid
flowchart TB
    subgraph pg [PostgreSQL 16 — one database and one login per service]
      pdb[(product_db<br/>categories, products+tenant_id,<br/>product_review_ratings)]
      odb[(order_db<br/>orders, order_items, outbox_event,<br/>processed_event, analytics_order,<br/>analytics_processed_event)]
      idb[(inventory_db<br/>stock, reservation, cancelled_order,<br/>outbox_event, processed_event)]
      paydb[(payment_db<br/>payments, idempotency_keys,<br/>outbox_event, processed_event)]
      rdb[(review_db<br/>reviews, outbox_event)]
    end
    product --> pdb
    order --> odb
    inventory --> idb
    payment --> paydb
    review --> rdb
    product --> rc[(Redis: product / products cache,<br/>keys prefixed by tenant)]
    gateway --> rl[(Redis: rate-limit buckets<br/>per tenant + user/IP)]
    notification --> none[no database:<br/>retry topics + DLT]
```
