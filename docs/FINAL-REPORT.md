# Final Report — Enterprise E-Commerce Platform

> G4 deliverable (Brief §10.4). It records what is built, how each requirement is met and verified, and what is
> still open. Every result below was produced by a real run; anything not yet measured says so.

## 1. What was built

- **Core:** config-server, eureka-server, api-gateway, product, order, payment, inventory, notification
  (Java 21, Spring Boot 3.5.16, Spring Cloud 2025.0.3, Maven multi-module, one parent POM).
- **Primary Bonus B2** (Order Analytics) and the extra Bonuses **B1** (review-service), **B3** (multi-tenant gateway
  and catalogue) and **B4** (low-stock alerts over SSE).
- **Delivery:** Docker Compose for the whole platform, a multi-stage image per service, GitHub Actions (secret scan,
  tests + JaCoCo gate, GHCR push, GitOps tag bump), kind manifests, one Helm chart for all services, ArgoCD.
- **Docs:** ADD (8 sections), backlog, charter, architecture diagrams, README with curl checks, performance report
  template, slide outline.

The branch `integration/merge-abc` merges `member-A`, `member-B` and `member-C` with `--no-ff` (git log keeps every
member's commits) and then fixes what the merge exposed — see §4.

## 2. Requirement traceability

| ID | How it is met | Verified by |
|---|---|---|
| FR-01 | public, paginated `GET /api/v1/products` (size ≤ 50, whitelisted sort) | `GatewaySecurityTest`, `ProductControllerTest`, E2E |
| FR-02 | ADMIN-only writes (gateway rule + `@PreAuthorize`) | `GatewaySecurityTest`, `ProductControllerTest`, E2E |
| FR-03 | `ProductView` projection with `categoryName` | `ProductRepositoryIT`, E2E |
| FR-04 | gateway Resource Server; 401/403 JSON | `GatewaySecurityTest`, E2E (no token, forged token) |
| FR-05 | `POST /orders` → 201 `{orderId, PENDING}`; order + outbox in one transaction | `OrderSyncIntegrationTest`, `OrderServiceTest`, E2E |
| FR-06 | Feign stock check with Retry/CB/TimeLimiter/Bulkhead; 409 / 503, nothing saved | `OrderSyncIntegrationTest` (201, 409, 500→503, timeout→503), E2E |
| FR-07 | atomic reserve per order line; release on `PaymentFailed`/`OrderCancelled` | `InventorySagaIT`, `InventoryServiceTest`, E2E |
| FR-08 | `payments.order_id` unique + `Idempotency-Key` store | `PaymentIdempotencyIT`, `PaymentSagaKafkaIT`, E2E |
| FR-09 | CONFIRMED on `PaymentCompleted`; CANCELLED + release on `PaymentFailed` | `OrderKafkaListenerTest`, `DeclinedPaymentIT`, `InventorySagaIT`, E2E |
| FR-10 | `GET /orders/{id}` owner-only (404 otherwise), `GET /orders` own list | `OrderServiceTest`, E2E |
| FR-11 | notification on CONFIRMED / CANCELLED, `@RetryableTopic` → `order-events.DLT` | `NotificationKafkaIT`, E2E (log line) |
| FR-12 | ADMIN `GET/PUT /inventory/{id}` | `InventoryControllerTest`, E2E |
| FR-13 | Redis rate limiter per client (and tenant) on public routes, 429 `RATE_LIMITED` | `RateLimitIT`, E2E |
| FR-14 | order-service client-credentials token on the stock check; inventory requires SERVICE | `ServiceTokenProviderTest`, `InventoryControllerTest`, E2E |
| FR-15 | Redis cache-aside, eviction on every write, tenant-prefixed keys | `ProductCacheIT`, E2E |
| FR-16 | Bonus B2 (primary) + B1, B3, B4 | see §3 |
| NFR-01 | payment down → orders wait PENDING in Kafka; reservations of PENDING orders are never swept | `InventorySagaIT` (pending reservation kept); chaos demo: see §5 |
| NFR-02/03 | k6 smoke/load/stress scripts with the targets as thresholds | `docs/PERFORMANCE-REPORT.md` — see §5 |
| NFR-04 | no secrets in Git (`.env`, K8s Secrets, gitleaks in CI), JWT at the gateway, uid 10001, one ServiceAccount per service without token | CI `secrets-scan`, Dockerfiles, Helm chart, `helm template` + kubeconform |
| NFR-05 | immediate release + sweeper for cancelled orders; evidence query in ADD §4 | `StockRepositoryTest`, `InventorySagaIT` |
| NFR-06 | Brave → Zipkin, Kafka observation, outbox continues the stored trace, ECS JSON logs | E2E (Zipkin trace services, JSON log line) |
| NFR-07 | JaCoCo ≥ 60 % on `*.application` per module (build fails otherwise); Testcontainers in every DB-owning service | `mvn verify` (§4) |
| NFR-08 | `docker compose up`; `helm install` / ArgoCD | E2E on compose; kind + ArgoCD: all Applications Synced, self-heal shown, full Healthy blocked by the laptop's memory (§5) |
| NFR-09 | Flyway in every DB service, `ddl-auto=validate`; all APIs under `/api/v1` | Testcontainers ITs run the migrations |
| NFR-10 | outbox (at least once) + idempotent consumers + DLT | `PaymentSagaKafkaIT`, `NotificationKafkaIT`, `InventorySagaIT`, listener tests |

## 3. Bonus features

| Bonus | Core delivered | Verified by |
|---|---|---|
| B2 Order Analytics (primary) | read model from order events, `GET /api/v1/analytics/summary` (ADMIN), Grafana dashboard with 3 panels, idempotent projection | `OrderAnalyticsProjectorIT`, `AnalyticsControllerTest`, E2E |
| B1 Reviews & Ratings | review-service with own DB/Flyway/Helm values/CI entry; one review per product and customer; paginated list; average + count via `ReviewSubmitted` | `ReviewControllerTest`, `SubmitReviewServiceIT`, `ProductRepositoryIT`, `ProductCacheIT`, E2E |
| B3 Multi-Tenant Gateway | tenant from the JWT claim, `X-Tenant-Id` forwarded, `tenant_id` on products, isolation proved by tests, per-tenant rate limit | `GatewaySecurityTest`, `ProductRepositoryIT`, `ProductCacheIT`, E2E |
| B4 Real-Time Inventory Alerts | `LowStock` on the threshold crossing (deduplicated per product), SSE stream through the gateway | `InventorySagaIT`, `AlertBroadcasterTest`, `NotificationKafkaIT` (< 2 s to the broadcaster), E2E |

## 4. Integration findings fixed on this branch

| Finding (from merging A + B + C) | Requirement | Fix |
|---|---|---|
| notification expected an event envelope; producers send flat JSON + `eventType` header → every notice in the DLT | FR-11 | notification reads the shared contract; `OrderCancelled` carries `customerId` |
| B/C modules on standalone Boot 3.4.3 POMs; A on a 3.5.16 parent | build | every module on the parent POM |
| two `EurekaServerApplication` classes → jar packaging failed | NFR-08 | one main class; B's context test kept |
| payment tests could not start under the merged config import | NFR-07 | test `application.yml` shadows the main one |
| order + outbox not in one transaction (self-invoked `@Transactional`) | FR-05, Brief §4 | `TransactionOperations` |
| outbox `traceparent` was a random UUID; rows marked SENT without broker ack | NFR-06, NFR-10 | real trace context; SENT after ack |
| multi-item order released only its first product | FR-07 | one reservation per order line (V3) |
| 30 s sweeper released reservations of orders still waiting for payment | FR-09, NFR-01 | sweeper releases only cancelled orders' reservations |
| Kafka listener errors swallowed (no retry, no DLT) in order/inventory | NFR-10 | errors propagate to retry + `<topic>.DLT` |
| `X-User-Id` defaulted to `customer-1`; any order readable by id | FR-10 | header required; owner check |
| inventory had no security; Feign sent no token; static URL | Brief API surface, FR-14, Eureka | resource server; client credentials; discovery |
| no Bulkhead/TimeLimiter on the stock check | Brief §5 Resilience | all four guards |
| `postgres/postgres` default credentials in Git config | NFR-04 | per-service logins, passwords only from env |
| Compose / Kubernetes / Helm disagreed on namespace, env names, secrets, Keycloak version, image names | NFR-08 | one convention (`SPRING_*`, `ecommerce`, Keycloak 26, CI image names) |
| no secret scan in CI; ArgoCD tracked a branch nothing updated | NFR-04, GitOps | gitleaks job; CI merges `main` into `env/dev` and commits tags |
| no JSON logs; tracing missing in gateway/product/notification | NFR-06 | ECS JSON logs; tracing everywhere |
| B's H2-only tests in inventory | NFR-07 | Testcontainers PostgreSQL + Flyway |
| k6 order flow sent no `unitPrice` and ordered unstocked products | NFR-02 | k6 setup reads prices, tops up stock |
| k6 VUs all logged in as one user at once → Keycloak brute-force lockout, a login per iteration, Keycloak at 642 % CPU | NFR-02 | one login in `setup()`, refresh-token grant per VU |
| Zipkin in-memory store (500k spans) larger than its 160 MB heap → 15 OOM restarts under load, gateway p95 spikes | NFR-02, NFR-06 | `MEM_MAX_SPANS=20000` (compose + kind) |
| Async `@Retry` on the `CompletableFuture` stock check could not re-run the AOP chain on another thread → retries failed, circuit opened, 70 % of orders 503 under load | FR-06, NFR-02 | `@Retry` on the synchronous caller; new retry test |
| Kubernetes Kafka Service routed only to ready pods while Kafka needs its own Service to become ready → never ready on kind | NFR-08 | `publishNotReadyAddresses: true` |
| Kubernetes Prometheus config had a mis-indented duplicate target (introduced while adding review-service) → crash loop | NFR-06, NFR-08 | duplicate removed; config passes `promtool` |

## 5. Verification results

*Runs on the integration machine (Windows 11, Docker Desktop 29.6, Docker VM 12 CPUs / 8 GB), 2026-10-05.*

**Build and tests** (`mvn verify` in `maven:3.9-eclipse-temurin-21`, Testcontainers PostgreSQL / Redis / Kafka):

| Module | Tests | JaCoCo ≥ 60 % on `*.application` |
|---|---|---|
| config-server, eureka-server | 1 + 1 | met |
| api-gateway | 46 | met |
| product-service | 46 | met |
| inventory-service | 50 | met |
| order-service | 44 | met |
| payment-service | 74 | met |
| notification-service | 24 | met |
| review-service | 9 | met |

**End to end on docker compose** (all 21 containers healthy, every service registered in Eureka, `verify-l0.sh`
→ `L0 OK`, Prometheus scraping all 9 services, both Grafana dashboards provisioned): 51 of 52 scripted checks
passed; the one failure was a transient `000` (no response) on a single request that returned the expected 400
when repeated. Highlights:

- Order with two products: 201 PENDING → CONFIRMED; both reservations CONSUMED; exactly one payment; confirmation
  notice logged; another customer gets 404 for the order; out-of-stock order → 409 and no order row.
- One Zipkin trace containing api-gateway, order, inventory, payment and notification; JSON logs carry the trace id.
- NFR-01 chaos: payment-service stopped → the order stayed PENDING → payment-service started → CONFIRMED.
- Compensation: payment failure rate 1.0 → order CANCELLED, stock back to its previous value, reservation
  RELEASED, `InventoryReserved` + `InventoryReleased` in the outbox, cancel notice; NFR-05 query → 0 rows.
- FR-11 DLT: with notification sends failing, the event went through `order-events.retry-0..2` to
  `order-events.DLT` with the alert log.
- Payment idempotency, ADMIN/CUSTOMER rules, client-credentials call to `/check`, cache eviction, 429 under burst,
  B1 (201 / 409 / rating via event), B3 (tenant B catalogue, 404 across tenants, 403 mismatch, 400 unknown),
  B4 (alert in the admin SSE stream 684 ms after the stock change).

**Static checks of deployment assets:** `docker compose config` valid; `helm lint` 9/9 charts; `helm template`
output + infra manifests through kubeconform: 46 valid, 0 invalid; actionlint on the CI workflow: no findings.

**Load (k6), final:** all `load-test.js` thresholds pass — `GET /products` p95 **118 ms** (was 872 ms),
`POST /orders` p95 **223 ms**, catalogue **59.6 req/s**, errors **0 %**; smoke 236 requests, 0 failed. Three
bottlenecks were found and fixed with before/after numbers (k6 login storm against Keycloak brute-force protection,
Zipkin out-of-memory restarts, stock-check retry across threads); details in `docs/PERFORMANCE-REPORT.md` §4–§5.

**Stress (k6, 150 VUs, 11 min):** 89 720 requests, **0 errors**, no restarts. Throughput levels off at ≈ 150–160
req/s beyond ~70–100 VUs and latency grows instead (p95 ≈ 1.3 s at 150 VUs); the API gateway saturates first
(≈ 1.8 cores). Per-stage table in the Performance Report §4.3.

**Kubernetes (kind v0.33.0) + ArgoCD v3.5.3**, run with the repo's `kind-config.yaml`, `create-k8s-secrets.sh`,
infra manifests, Helm chart and ArgoCD `infra-app.yaml` + `services-appset.yaml`. ArgoCD read a *local* git server
(the repo URLs were substituted at apply time; nothing was pushed to GitHub) whose `env/dev` branch = this branch
+ one commit pointing the image tags at locally built images loaded into kind, i.e. what the CI `deploy-tags` job
commits.

- ArgoCD generated all 9 service Applications plus `infra`; **all 10 reached `Synced`** at the `env/dev` revision.
- **Self-heal shown:** `kubectl scale deploy/notification-service --replicas=0` → Application OutOfSync → automated
  sync (`initiatedBy.automated: true`, `autoHealAttemptsCount: 1`) → replicas back to 1 after 56 s.
- GitOps update shown: two manifest fixes were committed here, merged into `env/dev` and applied by ArgoCD
  (Kafka Service, Prometheus config — see §4).
- Hardening read off the running pods: uid/gid 10001, `runAsNonRoot`, read-only root filesystem, one ServiceAccount
  per service, no API token mounted (checked inside the gateway pod), no RBAC bindings, startup/readiness probes on
  `/actuator/health/*`, DB passwords from `<service>-secrets`. The gateway answered on NodePort 30080 from the host.
- **Not achieved: all Applications `Healthy`.** api-gateway, config-server, eureka-server and notification-service
  were Healthy; the database services, Kafka/Zookeeper and Keycloak kept failing probes. The kind node (all 17
  platform workloads + ArgoCD + control plane in Docker Desktop's 8 GB VM) was memory-starved: load average 142 on
  12 CPUs, 74 MB free, memory pressure (PSI) 60 % some / 26 % full, 10.7 M pages swapped in. This is a resource
  limit of the integration laptop, not a manifest error; the cluster was deleted afterwards.

## 6. Open items for the team

- Sign `docs/TEAM-CHARTER.md`; draw and photograph the three paper drawings into `docs/architecture/`.
- Record the S25 Architecture Review's two risks in ADD §6 and the peer-review result in the ADD header.
- Re-run k6 smoke/load/stress on the demo machine for the G3 evidence (the integration-laptop numbers are in the
  Performance Report).
- Kubernetes: give Docker Desktop more memory (≥ 12 GB; this laptop has 16 GB) or use a bigger machine, push the
  branch and create `env/dev` once (`git push origin main:env/dev`), then follow `deployment/argocd/README.md` to
  show all Applications Synced **and** Healthy.
- Capture a Zipkin trace screenshot and a Grafana panel screenshot for G3.
- Known technical debt: order-service is not a Resource Server (ADD D7.1); no Pact contract test; notification
  dedup is in memory; single Kafka broker; no NetworkPolicies; test JVMs take > 30 s to exit (Surefire kills the fork
  after all tests passed).
