# Final presentation — slide outline (S29)

> The deck is `docs/slides/capstone-final.pptx` (19 slides). Structure from Brief §12; every number on a slide comes
> from the team's own runs. The screenshots on slides 8, 14, 16 and 17 are in `docs/slides/screenshots/`, taken on the
> kind cluster running `main` (2026-10-07). The spoken script with commands and plan B is `docs/DEMO-SCRIPT-AR.md`.

| Slides | Presenter | Content |
|---|---|---|
| 1–3 | Nourhan (A) | Title · who presents what · what we delivered in four numbers |
| 4–8 | Mohamed (B) | Architecture · the order Saga · every failure has an outcome · decisions we can defend · **one trace across HTTP and Kafka (Zipkin)** |
| 9–10 | Nourhan (A) | Security · performance and the bottleneck fixed |
| 11–17 | Doaa (C) | Test evidence · what testing the whole system caught · Bonus B2 · **B2 live in Grafana** · laptop to cluster · **ArgoCD 10/10 Synced/Healthy** · **CI on `main` + Platform Overview** |
| 18 | Mohamed (B) | If we started again: lessons and the debt we left on purpose |
| 19 | All three | Live demo, then questions |

## Evidence behind each slide

- **3** — 9 services, 331 tests, E2E 39/39, `POST /orders` p95 at 20 VUs: `docs/FINAL-REPORT.md` §4, `docs/PERFORMANCE-REPORT.md`.
- **5–7** — ADD §5 (Decisions 5-1 … 5-4) and §6 (failure modes, Decision 6-1).
- **8** — Zipkin trace `6ac640ad…`: 6 services, 30 spans; outbox carries `traceparent` (NFR-06).
- **9** — `docs/PERFORMANCE-REPORT.md` (WebSession per request removed at the gateway).
- **10–11** — JaCoCo gate per module (CI run #31 + #21), Testcontainers per database-owning service.
- **13–14** — `OrderAnalyticsProjectorIT`, `AnalyticsControllerTest`; Grafana *Order Analytics (Bonus B2)*.
- **16** — Final Report §6 item 4: 10/10 Applications Synced/Healthy, self-heal ~22 s, order `CONFIRMED` through NodePort 30080.
- **17** — CI run #31 on `main` (every module green across #21 + #31), Grafana *Platform Overview*.
- **18** — Final Report §7 (technical debt we consciously left).

## Trade-offs to be ready to defend

- Choreography, not orchestration: the switch trigger is a branching business rule (ADD 5-1).
- At-least-once + `processed_event` dedup instead of exactly-once delivery (ADD 5-3).
- Stock is released by saga outcome, never by age (ADD 6-1).
- Rate limiter fails open when Redis is down: availability over protection (ADD D7.4).
- One shared Helm chart for all services; a single Kafka broker for the demo.
- On kind, ArgoCD reads `env/dev` from a local read-only git server because the repository is private.

## Individual Q&A preparation

Every member should be able to walk through: the Saga with both failure paths, how the outbox avoids loss and how
duplicates are absorbed, the Resilience4j order and the single fallback, the Keycloak issuer setup and FR-14,
Helm / ArgoCD / secrets and self-heal, the trace across Kafka, and each Bonus.
