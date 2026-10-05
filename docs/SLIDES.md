# Final presentation — slide outline (S29)

> Structure from Brief §12. Each slide lists what to show and where the evidence lives. Numbers marked *(measure)*
> must come from the team's own runs; do not present a number that was not measured.

## 1. Architecture (≈ 4 min)

1. **The problem** — shoppers, admins, operations; what "a stranger can run, deploy, load-test and question" means.
   Source: `docs/CHARTER.md`, ADD §1.
2. **Boundaries** — 9 services, database per service, one login per database; Bonus B2 inside order-service.
   Diagram: `docs/architecture/README.md` §1 (+ the team's paper drawing).
3. **Key decisions** — Choreography Saga (why, and the switch trigger: branching rules), Transactional Outbox in every
   producer, flat events routed by the `eventType` header, Resilience4j on the stock check. ADD §5, §3.2.
4. **Trade-offs we accepted** — order-service trusts the gateway's `X-User-Id` (ADD D7.1); notification dedup is in
   memory; one shared Helm chart; rate limiter fails open when Redis is down.

## 2. Implementation (≈ 3 min)

5. **Layers → requirements** — table FR-01…FR-16 / NFR-01…NFR-10 → code and test (`docs/FINAL-REPORT.md` §2).
6. **Test evidence** — test counts per module and the JaCoCo gate from the last `mvn verify` *(measure on the demo
   machine or show the CI run)*; Testcontainers per database-owning service.

## 3. Live demo (≈ 6 min)

7. **Order end to end** — `POST /orders` → 201 PENDING → `GET /orders/{id}` CONFIRMED; the confirmation log line.
8. **Failure path** — `PAYMENT_FAILURE_RATE=1.0` → CANCELLED + cancel notice; NFR-05 query returns 0 rows.
9. **Deployment** — kind + ArgoCD Synced/Healthy; `kubectl scale` reverted by self-heal.
10. **A trace** — one traceId in Zipkin across gateway, order, Kafka, inventory, payment, notification.
11. **k6** — the load result and the bottleneck fixed, before/after *(measure)*. `docs/PERFORMANCE-REPORT.md`.
12. **Bonus** — B2 dashboard + `GET /analytics/summary`; then B1 (review → average), B3 (tenant B gets 404 on tenant
    A's product), B4 (stock below 5 → alert in the `curl -N` stream within 2 s).

## 4. Lessons learned (≈ 2 min)

13. **What we would change** — freeze the event contract in code (a shared events module) on Day 1: two members
    built different event formats, found only at integration; integrate on `main` daily, not at the end.
14. **Open technical debt (conscious)** — order-service not a Resource Server; no Pact contract test; product
    rating sub-queries per read; notification dedup lost on restart; single Kafka broker; no NetworkPolicies.

## 5. Individual Q&A preparation

Every member should be able to walk through: the Saga with both failure paths, how the outbox avoids loss and how
duplicates are absorbed, the Resilience4j order and fallback, the Keycloak issuer setup, Helm/ArgoCD/secrets, and
each Bonus.
