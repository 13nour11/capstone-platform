# Architecture Decision Document — Enterprise E-Commerce Platform

> Brief §10.1: 8 mandatory sections. Every decision uses
> **DECISION · OPTIONS CONSIDERED · REASON · TRADE-OFF · WHAT WOULD MAKE US REVISIT**.
> One owner per section (docs/TEAM-GUIDE.md §1.3). Peer-review result (S25): `<Approved / Approved with changes / Rework>`.

---

## 1. Problem statement

<!-- Owner: A -->

**Users.** *Shoppers* browse a public catalogue and place orders from a browser or mobile client. *Admins* run the
catalogue and the stock. *Operations* (the team on call) must answer "where is order X and why?".

**Pain.** The labs left a platform built one topic at a time: security rebuilt twice, stock in memory, a Saga that
could lose or double-apply events, and no way for a stranger to run, deploy or load-test it. A shopper can be
charged for an order that is then lost; an admin cannot trust stock numbers; nobody can show that it holds under load.

**What we deliver.** One product, in its final shape: 8 services behind one secured gateway, an order flow that is
never lost and never charged twice, and the evidence that it runs on Kubernetes and meets its latency targets.

**How we measure success.**

| Outcome | Measure | Evidence |
|---|---|---|
| Shoppers can always browse | `GET /api/v1/products` p95 < 200 ms, ≥ 50 req/s through the gateway | k6 load report (NFR-02/03) |
| Ordering is fast and safe | `POST /api/v1/orders` p95 < 800 ms at 20 VUs; one charge per order | k6 report; duplicate-payment test (FR-08) |
| No lost orders | Payment down → orders stay PENDING, then complete or are compensated | chaos test (NFR-01) |
| No orphaned stock | 0 RESERVED rows older than 30 s for a CANCELLED order | SQL query in the test plan (NFR-05) |
| Only the right people change data | missing/invalid token → 401, wrong role → 403, on every protected route | `GatewaySecurityTest`, curl suite (FR-04) |
| A stranger can run it | `docker compose up` + `verify-l0.sh`; `helm install` / ArgoCD Synced-Healthy | live demo (NFR-08) |

---

## 2. Bounded context (Bonus)

<!-- Owner: C -->

---

## 3. API contract + events

<!-- Owner: B (events) · each service owner for its endpoints. Frozen on Day 1; changes need every consumer's approval. -->

---

## 4. Data model

<!-- Owner: C -->

---

## 5. Communication

<!-- Owner: B -->

---

## 6. Failure modes

<!-- Owner: B -->

---

## 7. Security & deployment

<!-- Owner: A -->

### 7.1 Roles per endpoint (enforced at the gateway, and again in each service)

| Endpoint | Access | Enforced by |
|---|---|---|
| `GET /api/v1/products`, `GET /api/v1/products/{id}` | public (rate limited) | gateway `permitAll` + `RequestRateLimiter`; product-service `permitAll` |
| `POST/PUT/DELETE /api/v1/products[/{id}]` | `ADMIN` | gateway `hasRole(ADMIN)`; product-service `@PreAuthorize` |
| `POST /api/v1/orders`, `GET /api/v1/orders[/{id}]` | `CUSTOMER` (own orders only) | gateway `hasRole(CUSTOMER)`; order-service owner check |
| `GET /api/v1/inventory/check` | service (`SERVICE` role, client credentials) | **not exposed**: gateway `denyAll`; inventory-service checks the role |
| `GET/PUT /api/v1/inventory/{productId}`, `/api/v1/payments/**`, `/api/v1/analytics/**` | `ADMIN` | gateway `hasRole(ADMIN)` + the service |
| `/actuator/health/**`, `/actuator/prometheus` | internal | not routed by the gateway; cluster network only |
| any other path | denied | gateway `anyExchange().denyAll()` |

401/403/429 bodies follow the same RFC 7807 contract as service errors (`code` = `UNAUTHORIZED` / `FORBIDDEN` / `RATE_LIMITED`).

**D7.1 — Where JWTs are validated**
- **DECISION:** Gateway *and* every service are OAuth2 Resource Servers (Keycloak JWKS, `iss` checked).
- **OPTIONS:** (a) gateway only, services trust `X-User-*`; (b) gateway + services.
- **REASON:** pods are reachable inside the cluster; a header is trivially forged by anything that gets past the gateway.
- **TRADE-OFF:** each request verifies the signature twice (cheap: JWKS is cached), and every service needs the issuer settings.
- **REVISIT:** if a service mesh with mTLS and authorization policies (Istio) is introduced, services could trust mesh identity instead.

**D7.2 — Identity headers**
- **DECISION:** the gateway removes every incoming `X-User-*` header, then sets `X-User-Id` (sub) and `X-User-Roles` from the validated token.
- **REASON:** downstream code and logs get the identity without parsing JWTs, and a client cannot inject an identity.
- **TRADE-OFF:** the headers are convenience only; authorization decisions still use the JWT (D7.1).
- **REVISIT:** never relax the stripping; add `X-Tenant-Id` the same way if B3 is built.

**D7.3 — Token issuer in every environment**
- **DECISION:** Keycloak runs with a fixed `KC_HOSTNAME=http://localhost:8180`; services check `iss` against it and fetch keys from the in-network address (`KEYCLOAK_JWK_SET_URI`).
- **OPTIONS:** (a) `issuer-uri` discovery from each service; (b) fixed hostname + separate JWKS URI.
- **REASON:** with (a), a token fetched from the host (`localhost:8180`) and validated inside Docker/Kubernetes (`keycloak:8180`) fails with an `iss` mismatch — every call becomes 401.
- **TRADE-OFF:** the public hostname is configuration that must match in Compose, kind and the Helm values.
- **REVISIT:** when an ingress hostname exists for Keycloak, set `KC_HOSTNAME` and `KEYCLOAK_ISSUER_URI` to it.

**D7.4 — Rate limiting (FR-13)**
- **DECISION:** Redis `RequestRateLimiter` on the public product route, one token bucket per user id, else per client IP (20 req/s, burst 40, configurable).
- **REASON:** shared across gateway replicas; Redis is already in the stack (no new infrastructure).
- **TRADE-OFF:** if Redis is down the limiter **fails open** (requests pass) — availability over protection. All anonymous clients behind one NAT share a bucket.
- **REVISIT:** abuse in production → fail closed for anonymous traffic, or limit at the ingress.

### 7.2 Token propagation

| Call | Credential |
|---|---|
| Client → gateway → service | the user's bearer JWT, forwarded unchanged; the service validates it again |
| order-service → inventory-service `/check` (Feign) | **client credentials** of confidential client `order-service` (realm role `SERVICE`, FR-14), not the user's token |
| Saga events (Kafka) | no token: the broker is internal; identity fields (customer id) are copied into the event payload |

### 7.3 Secrets (NFR-04)

| Secret | Local (Compose) | Kubernetes | CI |
|---|---|---|---|
| DB passwords, Keycloak admin, `order-service` client secret, test-user password | git-ignored `.env` (template `.env.example`) | Secret `<service>-secrets` from `scripts/create-k8s-secrets.sh`, mounted with `envFrom` | — |
| GHCR push | — | `imagePullSecrets` (if packages are private) | GitHub Actions `GITHUB_TOKEN` |

The realm export contains no secret: `${ORDER_SERVICE_CLIENT_SECRET}` and `${KC_TEST_USER_PASSWORD}` are resolved
from the environment at import. Services read passwords only from environment variables.

### 7.4 Images, probes, Helm, ArgoCD

| Concern | Decision |
|---|---|
| Image | multi-stage (`maven:3.9-eclipse-temurin-21` → `eclipse-temurin:21-jre-alpine`), layered jar, `USER 10001`, `HEALTHCHECK` on `/actuator/health/liveness`, `MaxRAMPercentage=75` |
| Probes | `startupProbe` (≤ 3 min), `livenessProbe` `/actuator/health/liveness`, `readinessProbe` `/actuator/health/readiness` |
| Pod hardening | `runAsNonRoot` (10001), `readOnlyRootFilesystem` + `/tmp` emptyDir, no privilege escalation, drop `ALL`, seccomp `RuntimeDefault` |
| Least privilege | one ServiceAccount per service, no RBAC bindings, `automountServiceAccountToken: false` |
| Resources | requests 100m / 256Mi, limit 512Mi per service |

**D7.5 — Helm packaging**
- **DECISION:** one reusable chart (`deployment/helm/microservice`) + one values file per service; infrastructure as raw manifests.
- **OPTIONS:** (a) one chart per service; (b) one shared chart; (c) raw manifests everywhere.
- **REASON:** the 8 services differ only in image, port, env and Secret name; one chart keeps hardening identical everywhere (Brief: Helm for ≥ 2 services — here all 8).
- **TRADE-OFF:** a service that needs something unusual (e.g. a PVC) needs a new chart option or its own chart.
- **REVISIT:** when one service's needs diverge enough that the shared template grows conditionals for it.

**D7.6 — GitOps**
- **DECISION:** ArgoCD `infra` Application + `services` ApplicationSet, automated `prune` + `selfHeal`, tracking branch `env/dev` that CI updates with `:sha` tags.
- **OPTIONS:** (a) `helm upgrade` from CI; (b) ArgoCD tracking `main`; (c) ArgoCD tracking `env/dev`.
- **REASON:** drift is detected and reverted (demo: `kubectl scale` is undone); `main` stays protected because CI never pushes to it.
- **TRADE-OFF:** two branches to understand; a bad tag bump reaches the cluster without review (mitigated by the CI test gate).
- **REVISIT:** more environments → one `env/<name>` branch or overlay per environment.

**Replaced Defaults:** none. Eureka, Config Server, Keycloak Resource Server, Helm and ArgoCD are all kept as in the Brief.

---

## 8. Test & load plan + risks

<!-- Owner: C -->
