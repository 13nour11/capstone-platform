## Story

<!-- ID from docs/BACKLOG.md, e.g. L2-03. Title follows `capstone-Lx: short-description`. -->

## What changed and why

## How to verify

<!-- Test name(s) or the curl command that shows the story's Definition of Done. -->

## Checklist

- [ ] Rebased on the latest `main`
- [ ] Only files I own changed, or the owner approved
- [ ] `mvn -pl <module> -am verify` is green (tests + JaCoCo)
- [ ] New behaviour has a test; the story's Definition of Done is shown (test name or curl)
- [ ] No secrets, tokens or passwords; no commented-out code; no stray `System.out`
- [ ] Shared files: edits stay inside my own section marker
- [ ] Contract change? ADD §3 updated and consumers approved
- [ ] New service? Gateway route, Helm values, CI matrix and Keycloak client added
