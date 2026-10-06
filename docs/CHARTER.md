# Project Charter — Enterprise E-Commerce Platform

> Summarised from the Capstone Project Brief (Phase 4, S25–S29). The Brief is the source of scope; this page is the
> team's one-page reference to it.

## Vision

Rebuild the platform from the course, once and in its final form, as a product that someone outside the team can
run, deploy, load-test and question.

## Product

Customers browse a catalogue, place orders and are notified of the outcome. Admins manage the catalogue and the stock.

## Scope

| In scope | Out of scope |
|---|---|
| 8 platform services: config-server, eureka-server, api-gateway, product, order, payment, inventory, notification | Elasticsearch, extra databases or brokers, Axon, Event Sourcing |
| Infrastructure from the Starter Repo: PostgreSQL, Kafka + Zookeeper, Redis, Keycloak, Zipkin, Prometheus, Grafana | Any new infrastructure without a named problem in the ADD |
| Docker, CI to GHCR, Kubernetes (kind), Helm, ArgoCD | Service mesh (not required) |
| Observability, k6 load tests, documentation | |
| Primary Bonus B2 (Order Analytics); extra Bonuses B1, B3, B4 | Bonus Stretch goals |

## Requirements

Functional FR-01 … FR-16 and non-functional NFR-01 … NFR-10 as listed in Brief §3. Must + Should = the Core;
FR-16 = one Bonus. The traceability of each requirement to code and tests is in `docs/FINAL-REPORT.md`.

## Success measures

| Measure | Target |
|---|---|
| Product reads | `GET /api/v1/products` p95 < 200 ms (cached), ≥ 50 req/s through the gateway |
| Orders | `POST /api/v1/orders` p95 < 800 ms at 20 VUs; one charge per order |
| Availability | payment down → orders stay PENDING or are compensated, never lost |
| Consistency | no RESERVED stock older than 30 s after an order is CANCELLED |
| Quality | ≥ 60 % line coverage on service layers; ≥ 1 Testcontainers test per database-owning service |
| Deployability | `docker compose up` runs locally; Helm + ArgoCD run it on Kubernetes |

## Milestones (gates)

| Gate | Session | Layers |
|---|---|---|
| Kickoff | S25 | L0: repo, infra, ADD, backlog, team charter, paper drawings |
| G1 | S26 | L1 + L2: gateway, security, product, inventory, payment, order (sync) |
| G2 | S27 | L3 + L4: Saga + outbox + notification; Docker, CI, Kubernetes, Helm, GitOps |
| G3 | S28 | L5: tracing, Grafana, k6, one bottleneck fixed, Performance Report |
| G4 | S29 | L6: Bonus, docs, demo, final report, slides |

## Team and roles

Three members; roles (Tech Lead, DevOps/Security, QA/Docs) rotate at every gate — see `docs/TEAM-CHARTER.md` and
`docs/TEAM-GUIDE.md`. Every member commits in at least 3 services.

## Constraints

Java 21, Spring Boot 3.x, Spring Cloud, Maven multi-module; Keycloak Resource Server from day one; PostgreSQL +
Flyway; one Saga style, justified in the ADD; outbox, idempotency and versioning built in. No secrets in Git.
