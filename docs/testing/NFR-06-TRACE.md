# NFR-06 — one traceId across HTTP and Kafka

> Evidence captured on the Docker Compose stack, commit `9a81a2d`, 2026-10-05.
> Reproduce with the steps in §3; open the trace in Zipkin at <http://localhost:9411>.

## 1. What NFR-06 asks for

A single `traceId` must follow one order from the HTTP request, through the transactional outbox and
the Kafka hops, to every service that reacts to it. Without it, "where is order X and why?" can only
be answered by reading four services' logs side by side.

## 2. Result: satisfied

Order `a257d77b-2bd6-4bc0-9937-bd0199d294c8`, placed through the gateway and reaching `CONFIRMED`,
produced **one trace of 35 spans across 5 services**:

```
traceId 6ac41f941bfc4f5aecc748b5c894ea45
services: api-gateway, order-service, inventory-service, payment-service, notification-service

api-gateway           http post                                    SERVER    199.3 ms
  api-gateway           secured request
    api-gateway           http post                                  CLIENT    158.5 ms
      order-service         http post /api/v1/orders                 SERVER    152.7 ms
        order-service         authenticate bearertoken
        order-service         secured request
          order-service         outbox publish orderplaced                       9.4 ms
            order-service         order-events send                  PRODUCER     9.1 ms
              inventory-service     order-events receive             CONSUMER    63.4 ms
                inventory-service     outbox publish inventoryreserved          15.4 ms
                  inventory-service     inventory-events send        PRODUCER    16.3 ms
                    payment-service       inventory-events receive   CONSUMER    81.4 ms
                      payment-service       outbox publish paymentcompleted       8.8 ms
                        payment-service       payment-events send    PRODUCER     8.8 ms
                          order-service         payment-events receive CONSUMER  27.3 ms
                            order-service         outbox publish orderconfirmed  14.2 ms
                              order-service         order-events send  PRODUCER  14.2 ms
                                notification-service  order-events receive CONSUMER
```

The chain crosses the broker three times (`order-events`, `inventory-events`, `payment-events`) and
the outbox four times, and every hop stays on the same `traceId`.

**Database evidence of the same thing**, independent of Zipkin — the traceparent column of two
different services' outbox tables carries one traceId:

```
order_db      OrderPlaced        00-6ac41f941bfc4f5aecc748b5c894ea45-d238bff34684ea5b-01
order_db      OrderConfirmed     00-6ac41f941bfc4f5aecc748b5c894ea45-34fe7c21983aa3ce-01
inventory_db  InventoryReserved  00-6ac41f941bfc4f5aecc748b5c894ea45-02d63addb7310aa8-01
```

## 3. How to reproduce

```bash
# 1. stack up, then warm it (the first request after a restart can exceed the gateway timeout)
cd deployment/docker && docker compose up -d

# 2. place one order through the gateway and wait for the saga
#    (token: password grant for customer1, see docs/testing/RUNBOOK.md)
curl -X POST http://localhost:8080/api/v1/orders \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"items":[{"productId":2,"quantity":1}]}'

# 3. read the traceId the order was served under, from the outbox row
docker exec -i capstone-platform-postgres-1 psql -U postgres -c \
  '\c order_db' -c "select event_type, traceparent from outbox_event order by created_at desc limit 2;"

# 4. open it
#    http://localhost:9411/zipkin/traces/<traceId>
```

## 4. What makes it work

| Mechanism | Where |
|---|---|
| Services export Brave spans to Zipkin | `micrometer-tracing-bridge-brave` + `zipkin-reporter-brave` in **every** service |
| W3C `traceparent` rather than B3 | `management.tracing.propagation.type: w3c` (config-repo/application.yml) |
| The trace survives the outbox | the writing service stores the current `traceparent` on the outbox row; the publisher re-creates the context from it with `propagator.extract(...)` before sending |
| The trace survives the broker | `spring.kafka.template.observation-enabled` and `spring.kafka.listener.observation-enabled` |
| The scheduler does not drown it | `management.observations.enable.spring.scheduled: false`, so the 500 ms outbox pollers do not file a span each |

### What was wrong before

1. **Only 3 of 8 services reported to Zipkin at all.** api-gateway, product-service and
   notification-service had no tracing bridge on the classpath, so the trace ended at the gateway.
2. **The outbox stored a random UUID in `traceparent`**, not the actual W3C header, so the context
   could never be restored and every Kafka hop began a brand-new trace.

Both are fixed in commit `84755e1`.

## 5. Caveat

The spans above were captured on a laptop whose Zipkin reporter dropped spans under load
(`AsyncReporter: Dropped N spans`). For a clean capture, place **one** order on an idle stack, as in
§3, rather than looking for a complete trace during a k6 run.
