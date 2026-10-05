# Final Report — Enterprise E-Commerce Platform (Gate G4)

> Owner: B (TEAM-GUIDE §1). Brief §10.4: final repo, ADD, README, diagrams, slides, final report.
> Every claim below names its evidence: a test class, a document section, or a command anyone can run.
> Where something is **not** done or not yet shown live, this report says so (§6).

## 1. Summary

| | |
|---|---|
| **Product** | Enterprise e-commerce platform: browse a catalogue, place orders that are never lost and never charged twice, notify the customer; admins manage catalogue and stock. |
| **Services** | 8: config-server, eureka-server, api-gateway, product, order (+ B2 analytics read side), payment, inventory, notification |
| **Infrastructure** | PostgreSQL (database per service), Kafka, Redis, Keycloak, Zipkin, Prometheus, Grafana. Nothing added beyond the Brief. |
| **Saga** | Choreography over Kafka, transactional outbox in every producer, idempotent consumers, retry → DLT everywhere |
| **Bonus** | B2 — Order Analytics (CQRS read model + `GET /api/v1/analytics/summary` + Grafana dashboard) |
| **Functional scope** | FR-01 … FR-16: all implemented (FR-14 implemented, switched on by configuration, §6) |
| **Tests** | 252 tests in 8 modules; Kafka retry/DLT proven on an embedded broker; PostgreSQL behaviour with Testcontainers |
| **Not yet shown** | CI on `main`, images in GHCR, ArgoCD Synced/Healthy, the 20-VU load run (§6) |

## 2. Architecture

| Drawing (Brief §10.3) | File | Owner | Status |
|---|---|---|---|
| Service boundaries of the Bonus | [`docs/architecture/01-bonus-boundaries.svg`](architecture/01-bonus-boundaries.svg) | C | ✅ |
| Sequence: happy path + one failure path | [`docs/architecture/02-order-sequence.svg`](architecture/02-order-sequence.svg) | B | ✅ |
| Where data lives (table → database) | `docs/architecture/03-data-ownership.*` | A | ❌ open (§6); ADD §4 holds the same table in text |

```
 client ──JWT──► api-gateway :8080 ──► product :8081 (PG + Redis)
                  (Resource Server,      order :8082 (PG + outbox) ──Feign + R4j──► inventory :8084 (PG)
                   rate limit, roles)    inventory /{id} (ADMIN) · payment (ADMIN) · analytics (ADMIN)

 order-events ──► inventory ──► inventory-events ──► payment ──► payment-events ──► order, inventory
      └──► notification, analytics                               (every topic has a <topic>.DLT)

 config-server :8888 · eureka-server :8761 · Zipkin :9411 · Prometheus :9090 · Grafana :3000
```

The decisions behind this shape are in `docs/adr/ADD-TEAM.md`; the ones a reviewer is most likely to ask about:

| Decision | Choice | Why, in one line | ADD |
|---|---|---|---|
| Saga style | Choreography | 3 participants in a line, no branching; tracing answers "where is order X" | 5-1 |
| Event publishing | Transactional outbox, polling publisher, in order, inventory and payment | no lost and no phantom events; at-least-once is handled by 5-3 | 5-2 |
| Duplicates | `processed_event` in the same transaction + state checks | redelivery and republish are both harmless | 5-3 |
| Sync stock check | Retry → CircuitBreaker → Bulkhead → Feign timeout, one fallback | a slow or down inventory answers `503` fast; a sold-out product never opens the circuit | 5-4 |
| Stock release | by saga outcome, never by age | releasing by age oversold a slow-but-paid order (found and fixed) | 6-1 |
| Event schemas | a copy per service, add-only fields | independent deployability without a schema registry | 3-1 |
| B2 placement | package inside order-service, own tables `V50+`, own consumer group | no new deployable, chart or database | B2-1 |
| JWT validation | gateway **and** every service | pods are reachable inside the cluster | D7.1 |

**Replaced Defaults:** none. One variation inside a Default is recorded in ADD 5-4: the time limit on the Order →
Inventory call is the Feign read timeout, because Resilience4j's `@TimeLimiter` only applies to asynchronous methods.

## 3. Requirements traceability

### Functional (Brief §3)

| ID | Requirement | Status | Evidence |
|---|---|---|---|
| FR-01 | Public, paginated catalogue | ✅ | `ProductControllerTest`; README "Security & curl checks" |
| FR-02 | Product CRUD, ADMIN only | ✅ | `GatewaySecurityTest`, `ProductControllerTest` |
| FR-03 | Detail with category name (read model) | ✅ | `ProductQueryServiceTest`, `ProductRepositoryIT` |
| FR-04 | Keycloak sign-in; 401 on missing/invalid token | ✅ | `GatewaySecurityTest`, `OrderControllerTest`, `InventoryControllerTest` |
| FR-05 | Place order → `201 {orderId, PENDING}` at once | ✅ | `OrderSyncIntegrationTest`, `OrderControllerTest` |
| FR-06 | Sync stock check; no stock → rejected, payment untouched | ✅ | `OrderSyncIntegrationTest` (`409`, no row, one call, circuit stays closed) |
| FR-07 | Stock reserved, released if the order fails | ✅ | `InventoryServiceTest`, `StockRepositoryTest` |
| FR-08 | Payment exactly once per order | ✅ | `PaymentIdempotencyIT`, `UNIQUE(order_id)` |
| FR-09 | CONFIRMED on success; CANCELLED + stock released on failure | ✅ | `OrderServiceTest`, `DeclinedPaymentIT`, `InventoryServiceTest`; README demo |
| FR-10 | Read own order by id; list own orders | ✅ | `OrderControllerTest` (someone else's order → `404`) |
| FR-11 | Notifications; failed sends retried then DLT | ✅ | `NotificationKafkaIT.shouldRetryThenParkInDlt_whenSendKeepsFailing` |
| FR-12 | Admin views/adjusts stock | ✅ | `InventoryControllerTest` (ADMIN `200`, CUSTOMER `403`) |
| FR-13 | Rate limit per client | ✅ | `RateLimitIT` |
| FR-14 | Service-to-service client credentials | ✅ code · ⚠️ off by default | `ServiceTokenIntegrationTest`, `InventoryServiceTokenSecurityTest`; switch-on in README "Run locally" §4 |
| FR-15 | Product cache, evicted on every write | ✅ | `ProductCacheIT` |
| FR-16 | One Bonus Feature | ✅ B2 | `OrderAnalyticsProjectorIT`, `AnalyticsControllerTest`, Grafana *Order Analytics* |

### Non-functional (Brief §3)

| ID | Requirement | Status | Evidence |
|---|---|---|---|
| NFR-01 | Payment down → orders not lost | ✅ | events wait in Kafka; outbox; `PendingOrderSweeperTest` bounds a stuck saga; chaos steps in README "Orders & Saga" |
| NFR-02 | p95 GET products < 200 ms, POST orders < 800 ms | ✅ at the measured load | `docs/PERFORMANCE-REPORT.md` §4.2: 118 ms / 443 ms (reduced profile, see §6) |
| NFR-03 | ≥ 50 req/s reads | ✅ | Performance Report §4.3: ≈ 78 req/s on reads during the stress ramp |
| NFR-04 | No secrets in Git, JWT at gateway, non-root, SA per service | ✅ | gitleaks + non-root guard in CI, `.env` git-ignored, Helm ServiceAccount per service |
| NFR-05 | No orphaned reservation > 30 s after CANCELLED | ✅ | `ReservationSweeperTest`, `StockRepositoryTest`; NFR-05 query in README |
| NFR-06 | One traceId across HTTP and Kafka; JSON logs with traceId | ✅ | `traceparent` on every outbox row (`OutboxPublisherTest`); logstash JSON logs with `traceId`/`spanId`/`service` |
| NFR-07 | ≥ 60 % line coverage on service layers; ≥ 1 Testcontainers test per DB service | ✅ configured | JaCoCo gate in the parent POM and CI; `*IT`/`*RepositoryTest` per DB-owning service. Proof on CI pending (§6) |
| NFR-08 | `docker compose up`; `helm install` on Kubernetes | ✅ compose · ⚠️ cluster | `scripts/verify-l0.sh`; Helm chart + values; live cluster demo pending (§6) |
| NFR-09 | Flyway; every public API under `/api/v1` | ✅ | `ddl-auto=validate` everywhere; ADD §3.1 |
| NFR-10 | At-least-once + idempotent consumers + DLT | ✅ | `OrderSagaDeadLetterIT`, `InventorySagaDeadLetterIT`, `PaymentSagaKafkaIT`, `NotificationKafkaIT` |

## 4. Quality evidence

| Module | Tests | Notes |
|---|---|---|
| config-server | 1 | context loads with the native config-repo |
| eureka-server | 1 | context loads |
| api-gateway | 32 | security rules, routes; `RateLimitIT` on Redis (Testcontainers) |
| product-service | 26 | 2 Testcontainers ITs (Flyway on PostgreSQL, Redis cache eviction) |
| order-service | 63 | 3 Testcontainers ITs; 4 embedded-Kafka ITs (retry → DLT); WireMock resilience and FR-14 tests. `order.application` line coverage **79 %** |
| payment-service | 74 | 11 Testcontainers ITs (idempotency, Saga over Kafka, DLT) |
| inventory-service | 45 | 6 Testcontainers ITs; 3 embedded-Kafka ITs; security slice tests. `inventory.application` line coverage **85 %** |
| notification-service | 10 | `NotificationKafkaIT` (retry then DLT) |
| **Total** | **252** | 24 of them are Testcontainers tests that need Docker |

`mvn verify` runs unit, slice and integration tests together so JaCoCo sees one report; the build fails a module
whose `*.application` line coverage is below 60 % (parent POM, NFR-07). In the final review the gate turned out to be
**silently off** for the four B modules (their surefire `argLine` dropped the JaCoCo agent); fixed, and the numbers
above are measured with it on. Last full run without Docker: 228 passed, 0 failed; the 24 Testcontainers tests could
not start their containers there and run in CI. Testcontainers tests need Docker; the Kafka
dead-letter ITs use an embedded broker and run anywhere.

**Load (Performance Report, owner A):** smoke 0 errors; load (reduced profile) `GET /products` p95 118 ms,
`POST /orders` p95 443 ms, 0 % errors; stress to 150 VUs 97.9 req/s at 1.7 % errors, degrading without collapsing.
Bottleneck found and fixed: the second remote call (price lookup) on the order path, now cached for 60 s
(p95 600 → 443 ms, measured as an A/B on identical setup).

## 5. Final hardening (G4 review)

A review of the code against the Brief and our own ADD before G4 found these gaps. Each is fixed, tested, and recorded
in the ADD.

| # | Gap | Risk | Fix | Evidence |
|---|---|---|---|---|
| 1 | order and inventory Kafka listeners caught every exception and only logged it | a transient DB error silently dropped a Saga step (NFR-10) | errors reach `DefaultErrorHandler`: 3 retries, then `<topic>.DLT` + `ALERT`; unreadable JSON and permanent conflicts skip retries; a repeated outcome is a no-op | `OrderSagaDeadLetterIT`, `InventorySagaDeadLetterIT`, ADD F14/F15 |
| 2 | `OutOfStockException` counted as a circuit-breaker failure and was retried | three sold-out orders opened the circuit: **every** customer got `503` | ignored by breaker and retry; one fallback on the outermost layer | `OrderSyncIntegrationTest`, ADD 5-4 |
| 3 | no Bulkhead; the TimeLimiter config had no effect on a sync call | a slow inventory could hold every request thread | `@Bulkhead` (25) + Feign 1 s/2 s timeouts | `OrderSyncIntegrationTest`, `ResilienceConfigContractTest` |
| 4 | FR-14 described in the ADD but not in the code; inventory had no security at all | anything inside the network could change stock | inventory is a Resource Server (ADMIN on stock endpoints); order sends a client-credentials token | `InventoryControllerTest`, `ServiceTokenIntegrationTest` |
| 5 | `verify-l0.sh` ran `mvn test` with hard-coded counts and was not executable | L0 DoD not actually checked | checks infra health, config-server, Eureka registration; exit code | run against local config/eureka: green, red and registered paths |
| 6 | logs were plain text | NFR-06 asks for JSON logs with traceId | Boot structured logging (logstash) with `traceId`, `spanId`, `service` | sample line in `config-repo/application.yml` |
| 7 | ADD §4 required `flyway.out-of-order`, config did not set it | a new B migration would fail on a DB that already ran C's `V50` | set in order's block | `ResilienceConfigContractTest` |
| 8 | README described the old sweeper (release by age) | readers would rebuild the oversell bug (ADD 6-1) | README rewritten with a guarantees/evidence table | README "Orders & Saga" |
| 9 | surefire `argLine` in the four B modules dropped the JaCoCo agent | the 60 % gate (NFR-07) never ran; CI would fail these modules for a missing report | `@{argLine}` keeps the agent | `mvn verify`: "All coverage checks have been met" |

## 6. Open items before the final demo (owner, action)

These are outside Member B's files or need a running cluster. They are listed so nothing is silently missing.

| # | Item | Owner | Action |
|---|---|---|---|
| 1 | **All work is on side branches; `main` holds only the initial commit; no Pull Request exists** | all | merge through PRs with one teammate review each (Brief §5, §9). Until then CI has never run (it triggers on `main`) |
| 2 | CI green on `main`, images in GHCR | C | first run after item 1; JaCoCo report is the NFR-07 proof |
| 3 | ArgoCD tracks `env/dev`, which does not exist | A | `git push origin main:env/dev` after item 1 (deployment/argocd/README) |
| 4 | Live cluster: pods Ready, ArgoCD Synced/Healthy | A + C | kind on a ≥ 6 GB machine; screenshot for the slides |
| 5 | Switch FR-14 on | C (compose), A/C (Helm, Secret) | order-service: `ORDER_SERVICE_AUTH_ENABLED=true`, `ORDER_SERVICE_CLIENT_SECRET` (in a Secret its chart reads; today only `db-credentials`), `KEYCLOAK_TOKEN_URI`; inventory: `INVENTORY_REQUIRE_SERVICE_TOKEN=true` (README "Run locally" §4) |
| 6 | Team Charter: names, GitHub handles, signatures | A, signed by all | `docs/TEAM-CHARTER.md` still has `<name>` placeholders |
| 7 | ADD peer-review result | A | header line of `docs/adr/ADD-TEAM.md` still reads `<Approved / …>` |
| 8 | Drawing 3: where data lives | A | `docs/architecture/03-data-ownership.*` (ADD §4 has the content) |
| 9 | NFR-02 at the stated 20 VUs; per-stage stress numbers | A | rerun `k6/load-test.js` and `stress-test.js --out json=…` on ≥ 6 GB |
| 10 | Slides + two demo rehearsals | A with B + C | §8 below is the demo script |
| 11 | Pact contract test order ↔ inventory | B | **consciously not done**: recommended, not required. The contract is frozen in ADD §3 and exercised by WireMock in `OrderSyncIntegrationTest`. Revisit if a second team consumes inventory |

## 7. Technical debt we consciously left

| Debt | Why it is acceptable now | What would make us fix it |
|---|---|---|
| `processed_event`, `cancelled_order` and outbox `SENT` rows are never purged | small at Capstone volume | a table passes ~1 M rows (ADD 5-3) |
| DLT replay is manual | DLT traffic is rare and each record needs a human decision (e.g. refund for F15) | DLT volume > a few per day → a replay endpoint |
| Event classes are copied per service | independent deployability (ADD 3-1) | a renamed field breaks a consumer, or a 4th Saga participant |
| The stock pre-check does not reserve | the atomic reservation in the Saga is the real guard; worst case the order is created then `CANCELLED` (F7) | customer complaints about late cancellations |
| Single Kafka broker, replication factor 1 | local + kind demo only | any shared environment |
| Rate limiter fails open when Redis is down | availability over protection (D7.4) | abuse in production |

## 8. Demo script (15 minutes)

| Min | What | Command / place |
|---|---|---|
| 0–2 | Problem, boundaries, Saga choice | slides: ADD §1, drawing 01, Decision 5-1 |
| 2–4 | Platform up | `scripts/verify-l0.sh` → `L0 GREEN`; Eureka dashboard |
| 4–6 | Security | README "Security & curl checks": 200 public, 401, 403, 429 |
| 6–8 | Happy path | README "Orders & Saga" demo: `201 PENDING` → `CONFIRMED`; notification log line |
| 8–10 | Failure path | `PAYMENT_FAILURE_RATE=1.0` → `CANCELLED`, stock back; NFR-05 query = 0; `409` for no stock |
| 10–11 | One trace | Zipkin trace gateway → … → notification; same `traceId` in JSON logs |
| 11–12 | Deployment | `kubectl get pods`, ArgoCD Synced/Healthy (after §6 items 1–4) |
| 12–13 | k6 + the bottleneck | Performance Report §4.2 / §5 |
| 13–15 | Bonus B2 | `GET /api/v1/analytics/summary`, Grafana *Order Analytics* |

## 9. Lessons learned

1. **Merge small and early.** We built on one branch per member and merged at the end. The Brief warned against it:
   no PR history, CI that never ran on `main`, and integration bugs found late. Next time: one story per PR from day 1.
2. **An ADD claim is a test you have not written yet.** FR-14 and the `out-of-order` setting were in the ADD but not in
   the code until the final review. Every decision now names its test (ADD §6 evidence column).
3. **Resilience patterns compose badly by default.** A fallback on each layer and a business exception counted as a
   failure turned "sold out" into "service down". One fallback, outermost; business answers are not failures.
4. **A sweeper must use the saga's outcome, not a timer.** Releasing reservations by age oversold an order whose
   payment was merely slow (ADD 6-1). The service that knows the order's state decides when it is dead.
5. **Measure on the hardware the target assumes.** The first load runs measured a swapping laptop, not the platform
   (Performance Report §2.1). Check resources before quoting a number.
