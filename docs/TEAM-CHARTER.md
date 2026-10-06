# Team Charter

> One page, signed by all three members in S25 (Brief §9). Fill in every `<…>` together, then sign.

**Team:** `<team name>` · **Repository:** https://github.com/13nour11/capstone-platform · **Trainer:** Dr. ElSayed Baladoh

| Member | GitHub | Owns (docs/TEAM-GUIDE.md §1) |
|---|---|---|
| A — Nourhan Fahmy | [@13nour11](https://github.com/13nour11) | api-gateway · product-service · notification-service · Keycloak realm · Helm · ArgoCD · k6 |
| B — Mohammed Selim | [@M7mdselim](https://github.com/M7mdselim) | order-service · inventory-service · config-server · eureka-server · tracing |
| C — Doaa Amr | [@doaaamr1201](https://github.com/doaaamr1201) | payment-service · Bonus B2 · Dockerfiles · CI · Kubernetes · Grafana |

## 1. Working hours

- Core overlap: `<e.g. 19:00–21:00 every day>`; each member plans about 2 h/day outside it (Brief §2).
- Daily 10-minute stand-up at `<time>`: yesterday, today, blocked?

## 2. Communication

- Channel: `<WhatsApp / Discord / Slack>`; decisions go into a PR or the ADD, not only into chat.
- Response time during working hours: within 2 hours.

## 3. Pull requests (review SLA)

- Every change goes through a PR into `main` with one teammate's approval; `main` is protected.
- A PR is reviewed within **24 hours**; CI must be green before merge; squash and merge.
- Short-lived branches (≤ 2 days), `capstone-Lx/<story>` branch names, `capstone-Lx: short-description` commits.
- Pair work carries a `Co-authored-by:` trailer.

## 4. Disagreements

1. Discuss in the PR, with the trade-off written down.
2. Still open after one day: the rotating **Tech Lead** decides and records it in the ADD.
3. Architecture-level and still contested: ask the trainer at the next gate.

## 5. When a member is blocked

- Say so in the channel as soon as the block exceeds **2 hours**, with what was tried.
- Use the workaround in TEAM-GUIDE "Hand-offs" (stubs, `jwt()` in tests, direct ports) and keep working.
- A gate at risk is reported to the trainer early as **Amber with a recovery plan** — never a silent Red.

## 6. Roles rotate at every gate

| | G1 | G2 | G3 | G4 |
|---|---|---|---|---|
| A | Tech Lead | DevOps/Security | QA/Docs | Tech Lead |
| B | QA/Docs | Tech Lead | DevOps/Security | QA/Docs |
| C | DevOps/Security | QA/Docs | Tech Lead | DevOps/Security |

## 7. Non-negotiables

- No secrets in Git, ever (`.env`, Kubernetes Secrets, CI secrets).
- Never cut: tests, security rules, the compensation path, deployment.
- Every member can explain every service by the final presentation.

## Signatures

| Member | Signature | Date |
|---|---|---|
| A — Nourhan Fahmy | | |
| B — Mohammed Selim | | |
| C — Doaa Amr | | |
