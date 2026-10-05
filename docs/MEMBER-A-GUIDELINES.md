# Member A Operational Guidelines & Execution Rules

> **Scope:** Dedicated operational guidelines for **Member A** working on the Capstone Microservices Platform.
> **Sources of Truth:** `docs/TEAM-GUIDE.md` and *Capstone Project Brief (Trainee Handbook)*.
> **Guiding Principle:** Stay strictly within Member A's assigned scope. Never touch, modify, or format files owned by Member B or Member C.

---

## 1. Absolute File Ownership & Scope Boundaries

### ✅ What Member A Owns (Permitted Files)
You are permitted to create, edit, and commit code **only** within the following paths:

1. **Microservices & Edge**:
   - `platform/api-gateway/**` (100% owned)
   - `services/product-service/**` (100% owned)
   - `services/notification-service/**` (100% owned)

2. **Configurations**:
   - `config-repo/api-gateway.yml`
   - `config-repo/product-service.yml`
   - `config-repo/notification-service.yml`
   - `deployment/docker/keycloak/realm-export.json` (realm `ecommerce-platform`, clients, roles, test users)

3. **Deployment (GitOps)**:
   - `deployment/helm/**` (shared chart `microservice/` + `values/<service>.yaml`)
   - `deployment/argocd/**` (`infra-app.yaml`, `services-appset.yaml`)

4. **Load Testing**:
   - `k6/**` (`smoke-test.js`, `load-test.js`, `stress-test.js`, `lib/`)

5. **Repository Skeleton (Starter files, complete but never reformat)**:
   - `pom.xml` (root): one-line PRs only, see §6
   - `.gitignore` · `.gitattributes` · `.github/CODEOWNERS` · `.github/PULL_REQUEST_TEMPLATE.md`

6. **Documentation**:
   - `docs/TEAM-CHARTER.md`
   - `docs/TEAM-GUIDE.md`
   - `docs/PERFORMANCE-REPORT.md`
   - `docs/architecture/03-data-ownership.*` (drawing: where data lives, table → database)
   - Slides file under `docs/` (final presentation)
   - `docs/adr/ADD-TEAM.md`: Sections **1** (Problem Statement) and **7** (Security & Deployment)
   - `README.md`: **ONLY** under your assigned headings:
     - `## Security & curl checks`
     - `## Helm & GitOps`
     - `## Load tests`

---

### 🚫 Strictly Forbidden Paths (Owned by Member B or Member C)
You must **NEVER** edit, create, refactor, reformat, or delete files in these paths:

| Path / Component | Owner | Action if a change is needed |
|:---|:---:|:---|
| `services/order-service/**` (except `analytics/`) | **Member B** | Request via PR; the owner reviews (e.g. a bottleneck fix found by k6) |
| `services/inventory-service/**` | **Member B** | Request via PR or consume the existing API |
| `platform/config-server/**` · `platform/eureka-server/**` | **Member B** | Request via PR |
| `config-repo/application.yml` · `config-repo/order-service.yml` · `config-repo/inventory-service.yml` | **Member B / C** (per block) | Request via PR |
| `services/payment-service/**` | **Member C** | Payment is reached only through the gateway route |
| `services/order-service/.../analytics/**` | **Member C** | Add the gateway route for `/api/v1/analytics/**` in **your** `api-gateway.yml` when C asks |
| `*/Dockerfile` (all 8 Dockerfiles) | **Member C** | Helm consumes the images C publishes |
| `deployment/docker/docker-compose.yml` · `deployment/docker/prometheus/**` · `deployment/docker/grafana/**` | **Member C** | Request via PR |
| `deployment/kubernetes/**` · `scripts/create-k8s-secrets.sh` | **Member C** | Helm values reference the Secrets C creates |
| `.github/workflows/**` | **Member C** | Request via PR |
| `scripts/verify-l0.sh` · `scripts/e2e-check.sh` | **Member B** | Run them; do not edit |

---

## 2. Mandatory Package Architecture & Folder Design

Every service owned by Member A (`api-gateway`, `product-service`, `notification-service`) follows the uniform layered structure:

```
src/main/java/com/ecommerce/<service>/
├── <Service>Application.java
├── api/             # Controllers, Request/Response DTO records, ControllerAdvice (product)
├── application/     # Use case services, @Transactional boundaries
├── domain/          # Entities, Enums, Value objects, Domain exceptions
└── infrastructure/  # Repositories, cache config, Kafka listeners, security config

src/main/resources/
├── application.yml  # Minimal: spring.application.name + config-server import ONLY
└── db/migration/    # Flyway (product-service only): V<n>__<description>.sql

src/test/java/com/ecommerce/<service>/
# Mirrors main structure: *Test (unit/slice) and *IT (Testcontainers / embedded Kafka)
```

The gateway is reactive (Spring Cloud Gateway): keep its code under `filter/` and `security/` packages, no blocking calls.

### Dependency Flow Rules:
$$\text{api} \longrightarrow \text{application} \longrightarrow \text{domain} \longleftarrow \text{infrastructure}$$
* **Domain purity:** The `domain` package must NEVER import Spring Web, Redis, or Kafka classes.
* **DTO Isolation:** Never expose JPA entities in controllers; product reads go through the `ProductView` projection (FR-03).

---

## 3. Flyway Migration Versioning

* `product-service` owns `product_db` alone: versions start at **`V1__`** and only grow.
* **Rule:** Never edit an existing, merged migration file. Always add a new version.
* Every service runs `ddl-auto=validate`: Flyway is the only thing that creates schema.

---

## 4. Architectural Invariants & Clean Code Rules

1. **Gateway is the trust boundary (FR-04, D7.1–D7.2):**
   - OAuth2 Resource Server against Keycloak; `iss` checked, JWKS fetched in-network (`KEYCLOAK_JWK_SET_URI`).
   - Strip every incoming `X-User-*` header, then set `X-User-Id` / `X-User-Roles` from the validated token.
   - `/api/v1/inventory/check` is **never** routed (`denyAll`); any unknown path is denied.
   - 401 / 403 / 429 bodies use the same RFC 7807 shape (`code` = `UNAUTHORIZED` / `FORBIDDEN` / `RATE_LIMITED`).
2. **Rate limiting (FR-13):**
   - Redis `RequestRateLimiter`, one bucket per user id, else per client IP; defaults 20 req/s, burst 40.
   - Fails open if Redis is down (availability over protection, D7.4).
3. **Product cache (FR-15):**
   - Cache-aside in Redis; **every write evicts** the item key and the page keys.
   - Redis outage degrades to DB reads, never to errors.
4. **Notification consumer (FR-11, NFR-10):**
   - `@RetryableTopic` on `order-events` (4 attempts, exponential backoff), then `order-events.DLT` + `ALERT` log + `notifications.dlt` counter.
   - Reads the **frozen event contract** (ADD §3.2): flat JSON body, `eventType` in the Kafka header. Never require a field the producer does not send.
   - Dedup by `eventId`: a redelivered event never sends twice.
5. **Keycloak realm (no secrets in Git, NFR-04):**
   - Secrets in `realm-export.json` are `${PLACEHOLDERS}` resolved from the environment at import.
   - Confidential client `order-service` (client credentials, realm role `SERVICE`) serves FR-14.
6. **Helm & ArgoCD (L4):**
   - One shared chart; per-service differences only in `values/<service>.yaml`.
   - Every pod: non-root, startup/liveness/readiness probes on `/actuator/health/*`, own ServiceAccount, Secrets via `envFrom` (`secretName` + `extraSecretNames`).
   - ArgoCD: `automated`, `selfHeal`, `prune`; tracks `env/dev`.
7. **k6 (L5, NFR-02/03):**
   - Token fetched once in `setup()`; never hard-code a password (`K6_PASSWORD`).
   - Raise the gateway rate limit for the run and **say so** in the Performance Report.
   - Every number in the report comes from a real run; state the machine and the profile.
8. **Constructor Injection Only:** `private final` fields; zero `@Autowired` on fields.

---

## 5. Scope Reference & Out-of-Scope Constraints

* **Reference Documents:**
  1. `docs/TEAM-GUIDE.md`
  2. *Capstone Project Brief (Trainee Handbook)*
  3. `docs/adr/ADD-TEAM.md` §1, §3 (contract), §7
* **Strictly Out of Scope (Do Not Add):**
  - No Elasticsearch, MongoDB, or extra databases.
  - No extra message brokers; no Axon / Event Sourcing.
  - No Istio unless the ADD names a real problem it solves.
  - No personal branch merges directly to `main` without PR review.

---

## 6. Daily PR & Git Workflow for Member A

* **Branch Naming:** `capstone-L<n>/<story-id>-<short-description>` (e.g., `capstone-L1/L1-01-gateway-security`)
* **Commit Format:** `capstone-L<n>: <short-description>` (e.g., `capstone-L4: helm-chart-and-argocd`)
* **Root POM rule:** a new dependency or version is a **separate one-line PR** (`capstone-Lx: pom-add-<lib>`), merged the same day; never reorder or reformat.
* **PR Limit:** $\le 400$ changed lines per PR. Squash and merge into `main`.
* **Your stories:** L1-01, L1-02, L3-04, L4-02 (Helm/ArgoCD), L5-02 (`docs/BACKLOG.md`).
* **Daily Routine:**
  ```bash
  git switch main
  git pull origin main
  git switch -c capstone-L<n>/<task-name>
  ```
  Always rebase on `origin/main` before opening or updating a PR.
* **Before a gate:** `scripts/verify-l0.sh` and `scripts/e2e-check.sh` green on a clean `main`.
