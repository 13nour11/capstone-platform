# Team Guide — Ownership, Git Workflow, Code Rules

> One page every member follows from S25 to S29. Reference: *Capstone Project Brief* (§ numbers below).
> Goal: finish in **one week**; three people work in parallel, merge every day, and **never** fight a merge conflict.

**Members:** A = `<name>` · B = `<name>` · C = `<name>` (replace once the team is formed)

---

## 1. Who owns what

Every file has **one owner**. Only the owner edits it. Anyone else who needs a change opens a small PR and the owner reviews it (enforced by `CODEOWNERS`, §8).

| | **A** | **B** | **C** |
|---|---|---|---|
| Services | `api-gateway` · `product-service` · `notification-service` | `order-service` (sync + Saga + outbox) · `inventory-service` · `config-server` · `eureka-server` | `payment-service` · `order-service/analytics` (Bonus B2) |
| Platform work | Keycloak realm · Helm chart · ArgoCD | Tracing + JSON logs · L0 infra check | Dockerfiles · CI · Kubernetes · Grafana |
| Docs | Team Charter · Performance Report · k6 | Backlog · Final Report · README | ADD owner (sections split, §1.3) |

Each member commits in **≥ 3 services** (Brief §9, 40 % of the individual grade).

### 1.1 Every file in the repo

`Starter` = the file comes with the Starter Repo template. Complete it, but **never reformat it**.

| File / folder | Owner | Source | Day |
|---|---|---|---|
| `pom.xml` (root) | A | Starter | 1 |
| `.gitignore` · `.gitattributes` · `.github/CODEOWNERS` · `.github/PULL_REQUEST_TEMPLATE.md` | A | Starter | 1 |
| `README.md` | B (each member writes their own section, §4.2) | Starter | every day |
| `platform/config-server/**` · `platform/eureka-server/**` | B | Starter (READY) | 1 |
| `platform/api-gateway/**` | A | Starter | 2–3 |
| `services/product-service/**` | A | Starter | 2–3 |
| `services/inventory-service/**` | B | Starter | 2–5 |
| `services/order-service/**` (except `analytics/`) | B | Starter | 2–5 |
| `services/order-service/.../order/analytics/**` | C | Team | 5–7 |
| `services/payment-service/**` | C | Starter | 2–5 |
| `services/notification-service/**` | A | Starter | 4–5 |
| `*/Dockerfile` (all 8) | C | Starter | 4–5 |
| `config-repo/application.yml` | B | Starter | 1, 6 |
| `config-repo/<service>.yml` | owner of that service | Starter | per service |
| `deployment/docker/docker-compose.yml` | C | Starter (READY) | 1 |
| `deployment/docker/keycloak/realm-export.json` | A | Starter (READY) | 2–3 |
| `deployment/docker/prometheus/prometheus.yml` | C | Starter (READY) | 6 |
| `deployment/docker/grafana/**` (provisioning + both dashboards) | C | Team | 5–7 |
| `deployment/kubernetes/**` (kind-config + infra manifests) | C | Team | 4–5 |
| `deployment/helm/**` (chart + values per service) | A | Team | 4–5 |
| `deployment/argocd/**` | A | Team | 4–5 |
| `.github/workflows/ci-template.yml` | C | Starter | 4–5 |
| `scripts/verify-l0.sh` | B | Starter (READY) | 1 |
| `scripts/create-k8s-secrets.sh` | C | Team | 4–5 |
| `k6/**` | A | Starter + Team | 6–7 |
| `docs/TEAM-CHARTER.md` | A | Team | 1 |
| `docs/BACKLOG.md` | B | Team | 1, then daily |
| `docs/adr/ADD-TEAM.md` | split by section (§1.3) | Team | 1 → 7 |
| `docs/architecture/` (3 drawings) | one drawing each (§1.3) | Team | 1 |
| `docs/PERFORMANCE-REPORT.md` | A | Team | 7 |
| `docs/FINAL-REPORT.md` | B | Team | 7 |
| `docs/TEAM-GUIDE.md` (this file) | A | Team | 1 |
| `docs/CHARTER.md` · `docs/adr/ADD-TEMPLATE.md` · `docs/BACKLOG-TEMPLATE.md` | nobody: keep unchanged | Starter | — |

### 1.2 Shared files: the dangerous ones

Five files are touched by more than one person. They get **section markers in the L0 PR** (§4.1), and each member writes **only inside their own block**. Two edits in different blocks never conflict.

| Shared file | Rule |
|---|---|
| `pom.xml` (root) | Add a dependency or version in a **separate one-line PR** (`capstone-Lx: pom-add-<lib>`), merged the same day. Never reorder or reformat. |
| `README.md` | One heading per area. Write only under your own heading. |
| `config-repo/application.yml` | Blocks: `# --- tracing/logging (B) ---` · `# --- kafka (B) ---` · `# --- management/metrics (C) ---` |
| `config-repo/order-service.yml` | Blocks: `# --- order (B) ---` · `# --- analytics (C) ---` |
| `docs/adr/ADD-TEAM.md` | One owner per section (§1.3). |

### 1.3 ADD sections and drawings

| ADD section (Brief §10.1) | Owner |
|---|---|
| 1 Problem statement · 7 Security & deployment | A |
| 3 API contract + events · 5 Communication · 6 Failure modes | B |
| 2 Bounded context (Bonus) · 4 Data model · 8 Test & load plan + risks | C |

| Drawing (Brief §10.3) | Owner | File name |
|---|---|---|
| Service boundaries of the Bonus | C | `01-bonus-boundaries.jpg` |
| Sequence: happy path + one failure path | B | `02-order-sequence.jpg` |
| Where data lives (table → database) | A | `03-data-ownership.jpg` |

---

## 2. Work plan: one week, step by step, per member

**Time budget:** the Brief estimates **100–120 team-hours** (§6). One week therefore means **≈ 6 hours per person per day**. At 2 h/day the week cannot hold the Core. In that case, cut scope in the Brief's order (Stretch → Bonus Could → FR-14 → FR-13 → FR-12 → reduce Bonus Core) and **never** cut tests, security rules, the compensation path or deployment.

**How to read it:** after **Day 1** (done together) **nobody waits for anybody**. Each member follows their own list top to bottom. **One row = one branch = one PR**, merged the same day. The "Files" column is the complete list of what that PR may touch. If a task seems to need a file outside that list, do not edit it: ask its owner (§1).

### 2.0 The week at a glance

| Day | A | B | C | Ends with |
|---|---|---|---|---|
| **1** | ← together: L0 PR · frozen contracts · ADD · drawings · backlog · charter → | | | L0 green, contracts frozen |
| **2** | Gateway + Keycloak client | Inventory | Payment REST + idempotency | 3 services with tests |
| **3** | Product | Order (sync part) | Payment consumer + outbox (reference) | **L1 + L2 done** |
| **4** | Notification + Helm chart | Order outbox + Inventory Saga | Dockerfiles + CI | images in GHCR |
| **5** | Helm values + ArgoCD | Order Saga consumers → **integration** | kind + infra + Secrets · start B2 | **Saga on `main`, L3 + L4 done** |
| **6** | k6 + bottleneck hunt | Tracing + chaos checks | Grafana + B2 | **L5 done** |
| **7** | Performance Report + slides | Final Report + README + ADD | B2 dashboard + ADD | **L6 done, demo rehearsed** |

### 2.1 Day 1: all three together, in this order

| # | Task | Who | Files |
|---|---|---|---|
| 1 | Repo from the Starter, protect `main`, add the trainer | A | GitHub settings |
| 2 | L0 running: `docker compose up` healthy, `verify-l0.sh` passes | B | `scripts/verify-l0.sh` (only if it needs a fix) |
| 3 | The 3 drawings, **before** the ADD | each one | `docs/architecture/01…03` |
| 4 | ADD with **frozen contracts** in §3 (events, endpoints, DTOs, error codes) | each owns sections (§1.3) | `docs/adr/ADD-TEAM.md` |
| 5 | **L0 PR**: root POM with 8 modules, CODEOWNERS, PR template, `.gitignore`, `.gitattributes`, README headings, section markers in shared files | A (with B + C reviewing live) | `pom.xml` · `.github/*` · `.git*` · `README.md` · `config-repo/application.yml` · `config-repo/order-service.yml` |
| 6 | Backlog (12–15 stories) + Team Charter signed | B · A | `docs/BACKLOG.md` · `docs/TEAM-CHARTER.md` |

From here on, each member works only from their own list below.

### 2.2 Member A: Gateway · Product · Notification · Helm · ArgoCD · k6

| # | Day | Task (one PR each) | Files | Waits for |
|---|---|---|---|---|
| A1 | 2 | Gateway: Resource Server, Keycloak role converter, routes, `X-User-*` headers, Redis rate limit, JSON 401/403 | `platform/api-gateway/**` · `config-repo/api-gateway.yml` | nothing |
| A2 | 2 | Keycloak: confidential client `order-service` + role for service calls (FR-14) | `deployment/docker/keycloak/realm-export.json` | nothing |
| A3 | 3 | Product: Flyway, CRUD, pagination, category projection, Redis cache + eviction, tests · README "Security & curl checks" | `services/product-service/**` · `config-repo/product-service.yml` · `README.md` (own section) | nothing |
| A4 | 4 | Notification: consumers, `@RetryableTopic`, DLT handler, alert log, tests | `services/notification-service/**` · `config-repo/notification-service.yml` | nothing (hand-made events) |
| A5 | 4 | Helm chart: deployment, service, ServiceAccount, probes, non-root, Secret refs + values for **one** service | `deployment/helm/microservice/**` · `deployment/helm/values/product-service.yaml` | nothing (local image + `kind load`) |
| A6 | 5 | Helm values for the other 7 services | `deployment/helm/values/*.yaml` | nothing |
| A7 | 5 | ArgoCD: `infra-app` + `services-appset` (automated, selfHeal, prune) · README "Helm & GitOps" | `deployment/argocd/**` · `README.md` (own section) | C5 for a cluster (until then: kind on your machine) |
| A8 | 6 | k6 smoke, load, stress (token fetched in `setup()`) → baseline run → find **one** bottleneck | `k6/**` | Saga on `main` (end of Day 5) |
| A9 | 6–7 | Bottleneck fix → re-run → before/after numbers | the fix goes as a PR **to the owner** of that file | A8 |
| A10 | 7 | Performance Report · README "Load tests" · slides · demo rehearsal (twice, with B + C) | `docs/PERFORMANCE-REPORT.md` · `README.md` (own section) · slides file in `docs/` | A9 |

### 2.3 Member B: Inventory · Order (sync + Saga) · Config/Eureka · Tracing

| # | Day | Task (one PR each) | Files | Waits for |
|---|---|---|---|---|
| B1 | 2 | Inventory: Flyway (stock, reservation), `GET /check`, admin `GET/PUT /{productId}`, tests | `services/inventory-service/**` · `config-repo/inventory-service.yml` | nothing |
| B2 | 3 | Order (sync): entity + `V1`–`V49` migrations, `POST /orders`, `GET /orders/{id}`, `GET /orders`, Feign + Resilience4j (409 / 503), WireMock tests · README "Run locally" | `services/order-service/**` (not `analytics/`) · `config-repo/order-service.yml` (block B) · `README.md` (own section) | nothing (`jwt()` in tests until A2 is merged) |
| B3 | 4 | Outbox + publisher in **order**, same design as C's payment outbox (C3) | `services/order-service/**` | C3 (merged end of Day 3) |
| B4 | 4 | Inventory Saga: reserve on `OrderPlaced`, release on `PaymentFailed` / `OrderCancelled`, outbox, `processed_event`, NFR-05 sweeper, tests | `services/inventory-service/**` | nothing (hand-made events) |
| B5 | 5 | Order Saga consumers: CONFIRMED / CANCELLED, `processed_event`, tests · README "Orders & Saga" | `services/order-service/**` · `README.md` (own section) | nothing |
| B6 | 5 | **Integration (afternoon, all three):** full Saga on `main`, happy path + payment failure + DLT; each fixes only their own services | own services only | A4 · B3–B5 · C2–C3 merged |
| B7 | 6 | One trace across HTTP + Kafka (`traceparent` stored in the outbox), JSON logs with `traceId` | `config-repo/application.yml` (block B) · outbox code in order + inventory | nothing |
| B8 | 6 | NFR-01 chaos check (stop payment → PENDING → CONFIRMED) + NFR-05 query · README "Observability" | `README.md` (own section) | B6 |
| B9 | 7 | Final Report, README final pass, ADD final review | `docs/FINAL-REPORT.md` · `README.md` · `docs/adr/ADD-TEAM.md` (own sections) | nothing |

### 2.4 Member C: Payment · CI/Docker · Kubernetes · Grafana · Bonus B2

| # | Day | Task (one PR each) | Files | Waits for |
|---|---|---|---|---|
| C1 | 2 | Payment: Flyway, `POST /payments` with required `Idempotency-Key`, refund, failure-rate switch, tests | `services/payment-service/**` · `config-repo/payment-service.yml` | nothing |
| C2 | 3 | Payment consumer on `InventoryReserved` (key = `orderId`), tests with hand-made events | `services/payment-service/**` | nothing (frozen contract) |
| C3 | 3 | Outbox + publisher in **payment**: the **reference design** B copies on Day 4 · README "Payment" | `services/payment-service/**` · `README.md` (own section) | nothing |
| C4 | 4 | Dockerfiles for all 8 (multi-stage, non-root, `HEALTHCHECK`) + CI: test → build → push GHCR `sha`, JaCoCo 60 % | `*/Dockerfile` · `.github/workflows/ci-template.yml` | nothing |
| C5 | 5 | kind cluster + infra manifests + K8s Secrets script · README "Kubernetes" | `deployment/kubernetes/**` · `scripts/create-k8s-secrets.sh` · `README.md` (own section) | nothing |
| C6 | 5–6 | **B2:** projection on `order-events` (group `order-service-analytics`), `V50__…` migration, idempotent via `processed_event`, `GET /api/v1/analytics/summary` (ADMIN), metrics, no-double-count test | `services/order-service/.../analytics/**` · `V50+` migrations · `config-repo/order-service.yml` (block C) | nothing (hand-made events) |
| C7 | 6 | `traceparent` in the payment outbox (same design as B7) | `services/payment-service/**` | B7 design |
| C8 | 6 | Prometheus targets + Grafana provisioning + `platform-overview` panel | `deployment/docker/prometheus/prometheus.yml` · `deployment/docker/grafana/**` | nothing |
| C9 | 7 | B2 dashboard: 3 panels (orders/min by status, revenue, Saga failure rate) | `deployment/docker/grafana/dashboards/order-analytics.json` | C6 |
| C10 | 7 | ADD final: §2, §4, §8 · README "Bonus B2" | `docs/adr/ADD-TEAM.md` (own sections) · `README.md` (own section) | nothing |

### 2.5 The only cross-member requests

| From → To | What | When |
|---|---|---|
| B → C | Same `traceparent` change in the payment outbox (C7) | Day 6 |
| A → owner | Bottleneck fix inside someone else's service (A9) | Day 6–7 |
| C → A | Gateway route for `/api/v1/analytics/**` (ADMIN) | Day 5, one-line PR to `config-repo/api-gateway.yml` |
| anyone → A | New dependency in the root POM (one-line PR, §1.2) | any day |

### Roles rotate (Brief §9)

The trainer reviews at the gates. During the build week, roles follow the phase of the work:

| | Days 1–3 (L1 + L2 → G1) | Days 4–5 (L3 + L4 → G2) | Day 6 (L5 → G3) | Day 7 (L6 → G4) |
|---|---|---|---|---|
| A | Tech Lead | DevOps/Security | QA/Docs | Tech Lead |
| B | QA/Docs | Tech Lead | DevOps/Security | QA/Docs |
| C | DevOps/Security | QA/Docs | Tech Lead | DevOps/Security |

### Hand-offs: who waits for whom

Only the first two rows truly block, and both are finished on Day 1. For every other row the waiting member keeps working with the workaround.

| Needs | From | For | Ready by | Workaround while waiting |
|---|---|---|---|---|
| L0 PR merged (§4.1) | A + B + C | everyone | **Day 1** | none: done together |
| Event names + fields, endpoints, DTOs, error codes (ADD §3) | B (events) · each service owner (APIs) | everyone | **Day 1, frozen** | none: done together |
| Keycloak client `order-service` (client credentials) | A | B (Feign) | Day 2 | `spring-security-test` `jwt()` in tests; call inventory directly on its port |
| Gateway routes | A | B, C | Day 2 | call each service directly on its own port |
| Payment outbox (reference design) | C | B (order + inventory outbox) | Day 3 | — |
| Docker images in GHCR (CI) | C | A (Helm) | Day 4 | build locally + `kind load docker-image` |
| Cluster with infra | C | A (ArgoCD) | Day 5 | kind on your own machine |
| All Saga participants merged (order, inventory, payment, notification) | B · C · A | end-to-end Saga | **Day 5** | each consumer tested alone with Testcontainers Kafka + a hand-made event |
| Saga running on `main` | B · C · A | A (k6 numbers) · B (trace) · C (B2 data) | end of Day 5 | write k6 scripts, tracing config and B2 projection + tests against fake events |

A frozen contract changes only through a PR that updates ADD §3 **and** is approved by every consumer of it.

---

## 3. Git workflow

> ⚠️ **Do not keep one long branch per person and merge at the end.** That guarantees conflicts, and the Brief forbids it: branches live **1–2 days max** and merge into `main` through a PR (Brief §5, Decision guide 2).

### 3.1 Naming (Brief convention)

| What | Format | Example |
|---|---|---|
| Branch | `capstone-L<n>/<story-id>-<short>` | `capstone-L2/L2-03-payment-idempotency` |
| Commit | `capstone-L<n>: <short-description>` | `capstone-L2: add-payment-idempotency` |
| Pair work | trailer in the commit | `Co-authored-by: Name <email>` |

### 3.2 Daily loop

```bash
git switch main
git pull
git switch -c capstone-L2/L2-03-payment-idempotency
```
…work, commit small…
```bash
git fetch origin
git rebase origin/main
```
```bash
git push -u origin HEAD
```
Then open the PR. **Rebase on `main` every morning** and before every push. Resolve conflicts on your branch, never on `main`.

### 3.3 Pull Request rules

- One story per PR. Keep it small: **≤ ~400 changed lines**.
- Title = the commit convention. Use **Squash and merge**, so `main` gets one clean commit per story.
- **1 approval** from a teammate within **24 h** (Team Charter). The rotating Tech Lead decides on disagreement.
- CI must be green before merge.
- Delete the branch after merge.

### 3.4 PR checklist (copy into `.github/PULL_REQUEST_TEMPLATE.md`)

- [ ] Rebased on the latest `main`
- [ ] Only files I own changed, or the owner approved
- [ ] `mvn -pl <module> -am verify` is green (tests + JaCoCo)
- [ ] New behaviour has a test; the story's Definition of Done is shown (test name or curl)
- [ ] No secrets, tokens or passwords; no commented-out code; no stray `System.out`
- [ ] Shared files: edits stay inside my own section marker
- [ ] Contract change? ADD §3 updated and consumers approved

---

## 4. Conflict-proof rules

### 4.1 The L0 PR comes first

On S25 a single PR (owner A, pair-programmed with B and C) lands **before anyone branches**. It contains:
- root `pom.xml` with **all 8 modules** listed and all known dependency versions;
- `CODEOWNERS` (§8), the PR template, `.gitignore`, `.gitattributes`;
- `README.md` with every section heading already in place;
- section markers in every shared file (§1.2).

After that, nobody edits structure. People only add content inside their own area.

### 4.2 README sections

`## Run locally` (B) · `## Security & curl checks` (A) · `## Orders & Saga` (B) · `## Payment` (C) · `## Kubernetes` (C) · `## Helm & GitOps` (A) · `## Observability` (B) · `## Load tests` (A) · `## Bonus B2 — Order Analytics` (C)

### 4.3 Flyway versions in `order-service`

Two people add migrations to the same folder, so version numbers are reserved by range:

| Owner | Range | Example |
|---|---|---|
| B (orders, outbox, processed events) | `V1` – `V49` | `V1__create_orders.sql` |
| C (analytics) | `V50` and up | `V50__create_order_stats_hourly.sql` |

Never edit a migration that is already merged. Add a new one.

### 4.4 Formatting

- Reformat **only the lines you changed** (IntelliJ: *Settings → Tools → Actions on Save → Reformat code → Changed lines*).
- Never run "Reformat file" or "Optimize imports" on a file you don't own.
- 4 spaces, LF line endings (`.gitattributes` enforces LF).

### 4.5 Kafka names

| Item | Rule |
|---|---|
| Topics | `order-events` · `inventory-events` · `payment-events` (Brief §4) |
| Dead letter | `<topic>.DLT` |
| Consumer group | `<service-name>`, or `<service-name>-<purpose>` for a second group, e.g. `order-service-analytics` |
| Message key | `orderId` |

---

## 5. Code structure: the same in every service

```
src/main/java/com/ecommerce/<service>/
├── <Service>Application.java
├── api/              controllers, request/response DTOs (records), mappers, exception handler
├── application/      use-case services; @Transactional lives here
├── domain/           entities, enums, state transitions, domain exceptions
└── infrastructure/   repositories, Feign clients, Kafka listeners/publishers, outbox, config
src/main/resources/
├── application.yml   spring.application.name + config-server import only
└── db/migration/     V<n>__<snake_case>.sql
src/test/java/com/ecommerce/<service>/   same packages as main; *Test (unit/slice), *IT (Testcontainers)
```

Dependency direction: `api → application → domain ← infrastructure`. The domain imports nothing from Spring Web, Kafka or Feign.

### Naming

| Thing | Convention | Example |
|---|---|---|
| Controller / service | `<Noun>Controller` · `<Verb><Noun>Service` | `OrderController` · `PlaceOrderService` |
| DTO | `<Action><Noun>Request` · `<Noun>Response` (Java `record`) | `PlaceOrderRequest` |
| Event | past tense, Java `record` | `OrderPlaced` |
| Table / column | `snake_case`, plural table | `orders.customer_id` |
| Endpoint | `/api/v1/<plural-noun>` | `/api/v1/orders/{id}` |
| Config keys | prefix = service or feature | `payment.simulation.failure-rate` |
| Test method | `should<Result>_when<Condition>` | `shouldReturn409_whenStockIsMissing` |

---

## 6. Clean-code rules (checked in every review)

1. **Thin controllers:** validate, delegate, map. No business logic in controllers or Kafka listeners.
2. **Never expose entities:** DTO records in, DTO records out.
3. **Constructor injection only** (`private final` fields). No `@Autowired` on fields.
4. **No network I/O inside a DB transaction:** do the Feign stock check first, then open `@Transactional`. Write to the outbox, never `kafkaTemplate.send()` inside the transaction.
5. **One place for each state change:** `Order.confirm()` / `Order.cancel()` reject illegal transitions.
6. **Idempotency through the database:** unique constraints + catch `DataIntegrityViolationException`. Never check-then-insert.
7. **Never trust the client** for price or identity: price comes from product-service, the user id from the JWT.
8. **One error format:** RFC 7807 `ProblemDetail` with a stable `code`. No stack traces or SQL in responses.
9. **Logging:** SLF4J with context (`orderId`, `eventId`). Never log tokens, passwords or card data.
10. **No secrets in Git, ever:** environment variables, K8s Secrets (`scripts/create-k8s-secrets.sh`) and CI secrets only (NFR-04).
11. **No dead code:** no commented-out blocks, no unused classes, no `TODO` without a story id.
12. **Tests:** every PR carries tests. At least one Testcontainers test per DB-owning service (NFR-07). Coverage ≥ 60 % on service layers.

---

## 7. Before every gate (Tech Lead runs this)

- [ ] `main` builds green in CI
- [ ] Every story of this gate is merged, and no open branch is older than 2 days
- [ ] `git shortlog -sn -- services/ platform/`: every member has commits in ≥ 3 services
- [ ] Demo run once end to end from a clean `main`
- [ ] Gate status written: Green / Amber (+ recovery plan) / Red. Tell the trainer early.
- [ ] Teach-back: each member explains their services to the other two (15 min). The oral Q&A covers **any** part of the system.

---

## 8. CODEOWNERS (paste into `.github/CODEOWNERS`)

Replace `@member-a/b/c` with GitHub usernames. GitHub then asks the owner to review every PR that touches their files. **The last matching line wins.**

```
*                                                   @member-a @member-b @member-c

/pom.xml                                            @member-a
/.github/                                           @member-a
/.github/workflows/                                 @member-c
/README.md                                          @member-b

/platform/api-gateway/                              @member-a
/platform/config-server/                            @member-b
/platform/eureka-server/                            @member-b
/services/product-service/                          @member-a
/services/notification-service/                     @member-a
/services/inventory-service/                        @member-b
/services/order-service/                            @member-b
/services/order-service/src/main/java/com/ecommerce/order/analytics/   @member-c
/services/order-service/src/test/java/com/ecommerce/order/analytics/   @member-c
/services/payment-service/                          @member-c
**/Dockerfile                                       @member-c

/config-repo/                                       @member-b
/config-repo/api-gateway.yml                        @member-a
/config-repo/product-service.yml                    @member-a
/config-repo/notification-service.yml               @member-a
/config-repo/payment-service.yml                    @member-c

/deployment/docker/                                 @member-c
/deployment/docker/keycloak/                        @member-a
/deployment/kubernetes/                             @member-c
/deployment/argocd/                                 @member-a
/deployment/helm/                                   @member-a
/scripts/verify-l0.sh                               @member-b
/scripts/create-k8s-secrets.sh                      @member-c
/k6/                                                @member-a

/docs/                                              @member-a @member-b @member-c
/docs/BACKLOG.md                                    @member-b
/docs/FINAL-REPORT.md                               @member-b
/docs/TEAM-CHARTER.md                               @member-a
/docs/PERFORMANCE-REPORT.md                         @member-a
```
