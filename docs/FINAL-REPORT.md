# Final Report — Enterprise E-Commerce Platform (Gate G4)

> Owner: B (TEAM-GUIDE §1). Brief §10.4: final repo, ADD, README, diagrams, slides, final report.
> Every claim below names its evidence: a test class, a document section, or a command anyone can run.
> Where something is **not** done or not yet shown live, this report says so (§6).

## 1. Summary

| | |
|---|---|
| **Product** | Enterprise e-commerce platform: browse a catalogue, place orders that are never lost and never charged twice, notify the customer; admins manage catalogue and stock. |
| **Services** | 9: config-server, eureka-server, api-gateway, product, order (+ B2 analytics read side), payment, inventory, notification, review (B1) |
| **Infrastructure** | PostgreSQL (database per service), Kafka, Redis, Keycloak, Zipkin, Prometheus, Grafana. Nothing added beyond the Brief. |
| **Saga** | Choreography over Kafka, transactional outbox in every producer, idempotent consumers, retry → DLT everywhere |
| **Bonus** | B2 — Order Analytics (primary: CQRS read model + `GET /api/v1/analytics/summary` + Grafana dashboard); also B1 reviews, B3 tenants, B4 low-stock alerts (§10) |
| **Functional scope** | FR-01 … FR-16: all implemented, all shown live by `scripts/e2e-check.sh` (39/39 checks) |
| **Tests** | 331 tests in 10 modules, all green with `mvn verify` on the merged branch; JaCoCo gate met in every module; Kafka retry/DLT proven on an embedded broker; PostgreSQL, Redis and Kafka behaviour with Testcontainers |
| **Not yet shown** | CI on `main`, images in GHCR (§6). Kubernetes + ArgoCD Synced/Healthy was shown on kind before the merge (§10); it has to be re-run on the merged branch. Compose: L0 GREEN and E2E 39/39 on the merged branch (§4) |

## 2. Architecture

| Drawing (Brief §10.3) | File | Owner | Status |
|---|---|---|---|
| Service boundaries of the Bonus | [`docs/architecture/01-bonus-boundaries.svg`](architecture/01-bonus-boundaries.svg) · [JPG](architecture/01-bonus-boundaries.jpg) | C | ✅ |
| Sequence: happy path + one failure path | [`docs/architecture/02-order-sequence.svg`](architecture/02-order-sequence.svg) · [JPG](architecture/02-order-sequence.jpg) | B | ✅ |
| Where data lives (table → database) | [`docs/architecture/03-data-ownership.svg`](architecture/03-data-ownership.svg) · [JPG](architecture/03-data-ownership.jpg) | A | ✅ |

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
| FR-11 | Notifications; failed sends retried then DLT | ✅ | `NotificationKafkaIT` (retry → DLT, and the exact producer shape); e2e: confirmation and cancellation notices sent |
| FR-12 | Admin views/adjusts stock | ✅ | `InventoryControllerTest` (ADMIN `200`, CUSTOMER `403`) |
| FR-13 | Rate limit per client | ✅ | `RateLimitIT` |
| FR-14 | Service-to-service client credentials | ✅ on in Compose and Helm | `ServiceTokenIntegrationTest`, `InventoryServiceTokenSecurityTest`; e2e: `/check` → `401` without token, `403` with a customer token |
| FR-15 | Product cache, evicted on every write | ✅ | `ProductCacheIT` |
| FR-16 | One Bonus Feature | ✅ B2 | `OrderAnalyticsProjectorIT`, `AnalyticsControllerTest`, Grafana *Order Analytics* |

### Non-functional (Brief §3)

| ID | Requirement | Status | Evidence |
|---|---|---|---|
| NFR-01 | Payment down → orders not lost | ✅ | events wait in Kafka; outbox; `PendingOrderSweeperTest` bounds a stuck saga; chaos steps in README "Orders & Saga" |
| NFR-02 | p95 GET products < 200 ms, POST orders < 800 ms | ✅ at the stated load | Performance Report §7.2, canonical `load-test.js` at 20 VUs: **60.9 ms / 181.6 ms**, 0 errors |
| NFR-03 | ≥ 50 req/s reads | ✅ | Performance Report §7.2: 59.8 req/s held; §7.3: ~240 req/s total at saturation, 0 errors in 128 427 requests |
| NFR-04 | No secrets in Git, JWT at gateway, non-root, SA per service | ✅ | gitleaks + non-root guard in CI, `.env` git-ignored, Helm ServiceAccount per service |
| NFR-05 | No orphaned reservation > 30 s after CANCELLED | ✅ | `ReservationSweeperTest`, `StockRepositoryTest`; NFR-05 query in README |
| NFR-06 | One traceId across HTTP and Kafka; JSON logs with traceId | ✅ | `OutboxPublisherTest`; e2e: one Zipkin trace spans gateway, order, inventory, payment and notification; JSON log lines carry `traceId` |
| NFR-07 | ≥ 60 % line coverage on service layers; ≥ 1 Testcontainers test per DB service | ✅ configured | JaCoCo gate in the parent POM and CI; `*IT`/`*RepositoryTest` per DB-owning service. Proof on CI pending (§6) |
| NFR-08 | `docker compose up`; `helm install` on Kubernetes | ✅ compose · ⚠️ cluster | 16 containers healthy, `verify-l0.sh` GREEN; `helm lint`/`helm template` pass for all 8 services; a live cluster run is still to show (§6) |
| NFR-09 | Flyway; every public API under `/api/v1` | ✅ | `ddl-auto=validate` everywhere; ADD §3.1 |
| NFR-10 | At-least-once + idempotent consumers + DLT | ✅ | `OrderSagaDeadLetterIT`, `InventorySagaDeadLetterIT`, `PaymentSagaKafkaIT`, `NotificationKafkaIT` |

## 4. Quality evidence

| Module | Tests | Notes |
|---|---|---|
| config-server | 3 | serves config-repo, health, Prometheus metrics |
| eureka-server | 3 | registry API, health, Prometheus metrics |
| api-gateway | 47 | security rules, routes, tenant resolution (B3), no WebSession per request; `RateLimitIT` on Redis (Testcontainers) |
| product-service | 46 | Testcontainers ITs (Flyway on PostgreSQL, Redis cache eviction, tenant isolation). `product.application` **100 %** |
| order-service | 71 | Testcontainers, embedded-Kafka retry → DLT, WireMock resilience and FR-14. `order.application` **85 %**, `order.analytics.application` **93 %** |
| payment-service | 74 | Testcontainers ITs (idempotency, Saga over Kafka, DLT). `payment.application` **91 %** |
| inventory-service | 54 | Testcontainers, embedded-Kafka, security slices, B4 `LowStock`. `inventory.application` **78 %** |
| notification-service | 24 | `NotificationKafkaIT` (retry → DLT, producer contract, B4 alert < 2 s). `notification.application` **100 %** |
| review-service (B1) | 9 | Testcontainers IT (one review per customer and product). `review.application` **100 %** |
| **Total** | **331** | `mvn verify` with Docker on `integration/merge-abc-fixes`: **BUILD SUCCESS, 0 failures, 0 errors; JaCoCo gate met in every module** |

Live acceptance on the merged branch (fresh `docker compose down -v` + `up --build`, 2026-10-06): `scripts/verify-l0.sh` → **L0 GREEN** (8 infrastructure containers healthy, config-server serves all 9 files, 8 services registered) and
`scripts/e2e-check.sh` → **E2E GREEN, 39/39** (FR-13 included, on Windows Git Bash).

`mvn verify` runs unit, slice and integration tests together so JaCoCo sees one report; the build fails a module
whose `*.application` line coverage is below 60 % (parent POM, NFR-07). In the final review the gate turned out to be
**silently off** for the four B modules (their surefire `argLine` dropped the JaCoCo agent); fixed, and the numbers
above are measured with it on. Testcontainers tests need Docker; the Kafka
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
| 10 | **notification-service rejected every order event** (found only by the live end-to-end run) | no customer was ever notified (FR-11): it parsed an envelope while producers send the frozen flat contract, and `OrderCancelled` had no `customerId` | notification reads the header + flat body; `OrderCancelled` gains `customerId` (add-only) | `NotificationKafkaIT`, e2e FR-11 checks, ADD §3.2 |
| 11 | config-server and eureka-server exposed no Prometheus metrics | 2 of 8 scrape targets DOWN | Prometheus registry + endpoint | `*ApplicationTest.shouldExposePrometheusMetrics`, e2e Prometheus check |
| 12 | `ConfigServerApplicationTests` / `EurekaServerApplicationTests` never ran | the parent POM runs `*Test`/`*IT` only, so L0 had no test at all | renamed; now assert config served, registry API, health and metrics | 6 platform tests |
| 13 | `verify-l0.sh` and `create-k8s-secrets.sh` committed as non-executable | the documented commands failed with `Permission denied` | mode `100755` in Git | both run in this review |
| 14 | FR-14 was off in every deployment | the ADD's security model was not what ran | on in Compose and Helm (chart gained `extraSecretNames`); secret script creates `order-service-client` | e2e FR-14 checks, `helm template` |

## 6. Open items before the final demo (owner, action)

Everything that can be produced and checked from the repository is done. What is left needs a person, GitHub, or a
machine where Kubernetes can run.

| # | Item | Owner | Action |
|---|---|---|---|
| 1 | **All work is on `integration/merge-abc-fixes` (the merge of `integration/merge-abc` and `capstone-integration-fixes`); `main` still holds only the initial commit; no Pull Request exists** | all (team decision) | merge through PRs with one teammate review each (Brief §5, §9). CI triggers on `main`, so it has not run yet |
| 2 | CI green on `main`, images in GHCR | C | first run after item 1; the JaCoCo report is the NFR-07 proof (gate verified locally: all modules pass) |
| 3 | ArgoCD tracks `env/dev`, which does not exist yet | A | `git push origin main:env/dev` after item 1 (deployment/argocd/README) |
| 4 | Live cluster on the merged branch: pods Ready, ArgoCD Synced/Healthy, screenshot for the slides | A + C | shown on kind before the merge (§10, Docker Desktop with **12 GB**); repeat on the merged branch following deployment/kubernetes/README |
| 5 | Team Charter: working hours, channel, team name, **signatures** | all three | names and handles are filled in |
| 6 | ADD peer-review result (S25) | A | the header line of `docs/adr/ADD-TEAM.md` needs the real result from the other team |
| 7 | ~~Paper drawings~~ **done**: the trainer accepted computer-drawn diagrams | — | the three views are in `docs/architecture/` as SVG + JPG |
| 8 | Slides: open `docs/slides/capstone-final.pptx` in PowerPoint once and rehearse the demo twice | all | the deck was generated and validated, but could not be rendered for a visual check in the review environment |
| 9 | Pact contract test order ↔ inventory | B | **consciously not done** (recommended, not required). Item 10 in §5 is the case for adding one on the order-events contract |

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
5. **Run the whole system, not only the parts.** Every service's tests were green while no customer could be
   notified: producer and consumer had each implemented a different reading of one contract. Only a live end-to-end
   run (`scripts/e2e-check.sh`) caught it. Run it before every gate.
6. **Measure on the hardware the target assumes.** The first load runs measured a swapping laptop, not the platform
   (Performance Report §2.1). Check resources before quoting a number.

## 10. Merged from `integration/merge-abc`

`integration/merge-abc-fixes` merges this branch with `integration/merge-abc`, which had built the extra Bonuses and
run the platform on Kubernetes. Where both branches had rewritten the same code, the merge kept:

| Area | Kept from | Why |
|---|---|---|
| order-service code | `capstone-integration-fixes` | JWT Resource Server (customer = `sub`), saga timeout (`PendingOrderSweeper`), prices from product-service, Retry → CircuitBreaker → Bulkhead with Feign timeouts |
| inventory and notification code | `integration/merge-abc` | one reservation per order line, `InventoryReleased`, B4 `LowStock` + SSE stream; plus the `ALERT oversell` check and `InventorySagaDeadLetterIT` from this branch |
| compose, Helm values, Kubernetes manifests, CI, config-repo | `integration/merge-abc` | Keycloak 26, one DB login per service, review-service, `kafka:29092`, `publishNotReadyAddresses` on Kafka, gateway NodePort 30080, `CONFIG_SERVER_URL` read by every service |
| `scripts/verify-l0.sh`, `scripts/e2e-check.sh` | `capstone-integration-fixes` | adapted to the repo-root `.env`, review-service, `GATEWAY_HOST_PORT`, and a single parallel `curl` for the FR-13 burst |

### Bonus features

| Bonus | Core delivered | Verified by |
|---|---|---|
| B2 Order Analytics (primary) | read model from order events, `GET /api/v1/analytics/summary` (ADMIN), Grafana dashboard with 3 panels, idempotent projection | `OrderAnalyticsProjectorIT`, `AnalyticsControllerTest`, E2E |
| B1 Reviews & Ratings | review-service with its own DB, Flyway, Helm values and CI entry; one review per product and customer; paginated list; average + count via `ReviewSubmitted` | `ReviewControllerTest`, `SubmitReviewServiceIT`, `ProductRepositoryIT`, `ProductCacheIT` |
| B3 Multi-Tenant Gateway | tenant from the JWT claim, `X-Tenant-Id` forwarded, `tenant_id` on products, per-tenant rate limit | `GatewaySecurityTest`, `ProductRepositoryIT`, `ProductCacheIT` |
| B4 Real-Time Inventory Alerts | `LowStock` on the threshold crossing (once per product until stock recovers), SSE stream through the gateway | `InventorySagaIT`, `AlertBroadcasterTest`, `NotificationKafkaIT` (< 2 s to the broadcaster) |

Known limit: order-service prices items through product-service without a tenant, so it sees the default tenant's
catalogue (`tenant-a`). Ordering a `tenant-b` product fails (product-service answers 404 for it) until order-service
forwards the customer's tenant.

### Kubernetes + ArgoCD (run on `integration/merge-abc`, before the merge)

kind v0.33.0 + ArgoCD v3.5.3 with the repo's `kind-config.yaml`, `create-k8s-secrets.sh`, infra manifests, Helm chart
and `infra-app.yaml` + `services-appset.yaml`, against a local git server whose `env/dev` pointed the image tags at
locally built images:

- all 9 service Applications plus `infra` `Synced` and `Healthy` (Docker Desktop with 12 GB; with 8 GB the node was
  memory-starved and only 4/10 became Healthy);
- self-heal shown: `kubectl scale deploy/product-service --replicas=0` → automated sync → pod Ready after 20 s;
- from the host: token for `customer1` → `GET /api/v1/products` via NodePort 30080 → 200; `POST /api/v1/orders` →
  201 `PENDING` → `CONFIRMED`; without a token → 401;
- hardening read off the running pods: uid/gid 10001, read-only root filesystem, one ServiceAccount per service, no
  API token mounted, probes on `/actuator/health/*`.

The merge changed order-service, so this run has to be repeated on the merged branch (§6 item 4).
