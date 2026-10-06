# Performance Report (Layer L5, Gate G3)

> Owner: A. Every number in this report comes from a real k6 run on the machine described in §2.
> Cells marked `—` are filled after the run; never estimate them.

> **Read §2.1 and §2.2 before quoting any number here.** The load profile run was smaller than the
> canonical one, so the throughput target (NFR-03) is **not** assessed. The latency targets (NFR-02)
> were met on a warm system, and the §5 fix was measured as a controlled A/B on identical setup.

## 1. Targets (Brief NFR-02, NFR-03)

| Metric | Target | Measured by |
|---|---|---|
| `GET /api/v1/products` p95 (cached) | < 200 ms | `http_req_duration{name:GET /products}` |
| `POST /api/v1/orders` p95 at 20 VUs | < 800 ms | `http_req_duration{name:POST /orders}` |
| Gateway read throughput | ≥ 50 req/s | `http_reqs{scenario:browse}` |
| Error rate | < 1 % | `http_req_failed` |

## 2. Environment

| Item | Value |
|---|---|
| Machine | Intel Core i5-1135G7 (4 cores / 8 threads, 2.40 GHz), 7.7 GB RAM, Windows 11 Enterprise |
| Docker Desktop memory | 3.97 GB (Brief asks for ≥ 6 GB — see §2.1) |
| Deployment under test | Docker Compose, 13 of 16 containers (Grafana, Prometheus and notification-service stopped to free memory for the image build) |
| Commit | `7149d47` |
| Gateway rate limit during the run | `GATEWAY_RATE_LIMIT_REPLENISH_RATE=5000`, `BURST_CAPACITY=10000` |
| k6 version | `grafana/k6:latest` container, run on the compose network |

The gateway rate limiter (FR-13) works per client. All anonymous k6 traffic comes from one IP, so the limit is raised
for load runs. That is a test setting, not a production change, and is stated here so the numbers are honest.

k6 ran **inside the Docker network** against `http://api-gateway:8080`, not through the published host port. Host port
forwarding on this machine was adding seconds of latency of its own, which would have been measured as platform
latency.

### 2.1 Cold runs, and what they cost

The first runs of the session produced `GET /products` p95 of 4.1 s and `POST /orders` p95 of 10.9 s. Those numbers
are **not** representative and are kept here only as a warning:

- The host had **0.2–0.7 GB of 7.7 GB free**, so Windows was compressing and swapping while the JVMs were still cold.
  Service containers sat at 0.5–27 % CPU with latency in seconds: nothing was busy, everything was waiting.
- 64 of the failures in one run were `No servers available for service: order-service` — order-service had just been
  restarted and Eureka had not re-registered it. A test artefact, not a platform defect.
- The Docker build daemon crashed three times with `rpc error: EOF` from the same shortage.

After stopping Grafana, Prometheus and notification-service, letting the JVMs warm, and waiting for Eureka to settle,
the same profile produced the §4.2 figures with **zero errors**. Every number quoted below the A/B tables comes from
those warm runs. This is **risk R1 in §8 of the ADD**, observed rather than predicted: the platform is fine, the
laptop is the constraint.

### 2.2 Reduced load profile

The canonical profile in `k6/load-test.js` (60 req/s browse + 20 VUs ordering, 5 minutes) is **unchanged** and is what
should be run on proper hardware. It could not run here: at ~1 s average latency it would queue faster than the
machine could drain, and would most likely have taken the Docker daemon down again.

The profile actually run was:

| | Canonical (`k6/load-test.js`) | Run here |
|---|---|---|
| browse | 60 req/s, 5 min | **10 req/s, 90 s** |
| orders | 20 VUs, 5 min | **3 VUs, 90 s** |
| products ordered | ids 1–10 | **ids 1–4** (only these have stock rows; see §6) |

Both the before and after runs used this same reduced profile, so the §4.2 comparison is like for like.

## 3. Method

1. **Smoke** (`k6/smoke-test.js`, 1 VU, 1 min): every route answers, no errors.
2. **Baseline load** (reduced profile, §2.2): record the numbers below **before** any change.
3. **Find the bottleneck**: k6 per-route latency, service logs, and the Resilience4j circuit-breaker state.
4. **Fix one thing.**
5. **Re-run the same load test** on the same machine and compare.
6. **Stress** (`k6/stress-test.js`, ramp to 150 VUs): record where latency and errors break.

```bash
# as run, from the repo root (password from the repo-root .env)
docker run --rm --network capstone-platform_default \
  -v "$PWD/k6:/k6:ro" -v "$PWD/k6/results:/results" \
  -e K6_PASSWORD=... -e KEYCLOAK_URL=http://keycloak:8180 \
  -e GATEWAY_URL=http://api-gateway:8080 -e PRODUCT_IDS=1,2,3,4 \
  grafana/k6:latest run --summary-export /results/smoke.json /k6/smoke-test.js
```

## 4. Results

### 4.1 Smoke

| Check | Result |
|---|---|
| Requests / errors | 170 requests, **0 failed** |
| All checks passed | **Yes** — `token issued`, `list 200`, `item 200`, `order 201 PENDING`, `order readable` |

(The first, cold smoke run managed only 2 iterations at ~30 s each with 2 failures. See §2.1.)

### 4.2 Load: the fix measured as a controlled A/B

Both runs use the reduced profile (§2.2) on the **same 13 containers, same commit, same warm JVMs**, differing only in
`product.price-cache-ttl-seconds`. `0` disables the cache, so run B is the genuine "before".

| Metric | Target | B: cache off | A: cache on | Change |
|---|---|---|---|---|
| `POST /orders` p95 | < 800 ms | 600.4 ms | **442.6 ms** | **−26.3 %** |
| `POST /orders` p90 | — | 257.7 ms | **187.8 ms** | **−27.1 %** |
| `POST /orders` avg | — | 342.0 ms | **262.5 ms** | **−23.2 %** |
| `POST /orders` median | — | 52.4 ms | 49.0 ms | −6.6 % |
| `GET /products` p95 | < 200 ms | 108.1 ms | 117.9 ms | +9.1 % |
| `GET /products` median | — | 14.0 ms | 14.6 ms | +3.8 % |
| Throughput | ≥ 50 req/s (not assessed, §2.2) | 13.32 req/s | 14.21 req/s | +6.7 % |
| Dropped iterations | — | 69 | **0** | — |
| Error rate | < 1 % | **0.00 %** | **0.00 %** | — |

Reading this table:

- The fix targets the **order path only**, and that is what moved: p95 and p90 both down about 27 %.
- `GET /products` is the **control**. It does not touch the price cache, and it did not meaningfully change; the +9 %
  on p95 is run-to-run noise at a 14 ms median.
- **Dropped iterations went from 69 to 0**: with the second remote call removed, the 3 ordering VUs kept up with the
  schedule instead of falling behind.
- Both latency targets in NFR-02 are **met** in both runs on this machine at this load. The gap is the margin: 443 ms
  against an 800 ms budget leaves room, 600 ms leaves much less.

### 4.3 Stress

`k6/stress-test.js` unchanged: ramp 50 → 100 → 150 VUs over 11 minutes, 80 % catalogue reads / 20 % orders, cache on.

| Measure | Result over the whole ramp |
|---|---|
| Peak VUs | 150 |
| Requests | 64 985 in 11 min — **97.9 req/s** sustained |
| of which catalogue reads | 51 708 — **≈ 78 req/s** |
| of which orders placed | 6 523 |
| `GET /products` | median 496 ms, p90 1476 ms, **p95 2075 ms**, max 18.9 s |
| `POST /orders` | median 460 ms, p90 1337 ms, **p95 1905 ms**, max 18.2 s |
| Error rate | **1.70 %** — passes the stress threshold of < 5 % |
| `list 200` / `item 200` | 98.0 % each (25 333 / 25 854 and 25 335 / 25 854) |
| `order 201 PENDING` | **99.0 %** (6 458 / 6 523) |
| `order readable` | 99.98 % (6 457 / 6 458) |

**What this shows.** The platform does not collapse at 150 VUs: it degrades. Latency rises roughly 4–5× from the
20-VU figures in §4.2, errors reach 1.7 %, and ordering stays the most reliable path at 99 % — the circuit breaker and
the synchronous pre-check shed load rather than letting half-built orders through. Nothing timed out permanently and
no container died.

**Per-stage rows are not filled in.** The summary export is an aggregate over the whole ramp; splitting p95 by the 50 /
100 / 150 stages needs a time-series export (`--out json=...`), which was not captured. Re-run with that flag to fill
the three rows the template asks for.

## 5. Bottleneck found and fixed

| | |
|---|---|
| Symptom | `POST /orders` p95 600 ms against a 52 ms median, with 69 iterations dropped because the ordering VUs could not keep to schedule. On the cold runs the same path reached a 10.9 s p95 and tripped the inventory circuit breaker 9 times. |
| Evidence | The controlled A/B in §4.2: the same build, containers and load, toggled only by `product.price-cache-ttl-seconds` |
| Root cause | Placing one order makes **two serial remote calls** before the transaction opens: the stock check and a product price lookup. The price lookup was added this session to stop clients setting their own prices (§3.1 of the ADD), which was correct but doubled the remote work on the hot path. Every order paid for a price that changes very rarely. |
| Fix | `CachedProductPrices`, a 60 s TTL cache in front of `ProductServiceClient`, so a repeat order for the same product costs one remote call instead of two. Deliberately a **separate bean**: calling the resilient method from inside the same bean would bypass the Spring proxy and silently drop the circuit breaker, and counting cache hits as calls would skew the breaker's failure rate. `product.price-cache-ttl-seconds=0` disables it. |
| Trade-off | A price change takes up to 60 s to reach new orders. Orders already placed are unaffected: the amount is stored on the order row. |
| Effect | `POST /orders` **p95 600 ms → 443 ms (−26.3 %)**, p90 258 ms → 188 ms (−27.1 %), avg 342 ms → 263 ms (−23.2 %). Dropped iterations **69 → 0**; throughput +6.7 %. `GET /products`, which does not use the cache, was unchanged — the control that says the gain came from the fix and not from the machine having a good minute. |

Candidates ruled out while looking:

- **Missing indexes** — none. `orders(customer_id)`, `orders(status)`, `order_items(order_id)`,
  `outbox_event(status, created_at)` and `analytics_order(placed_at)` all exist in the migrations.
- **The gateway rate limiter throttling the test** — ruled out by raising it to 5000/s for the run (§2).
- **N+1 on product → category** — the product list already uses a single projection query.

## 6. Conclusions and remaining risks

### Verdicts

| NFR | Target | Verdict |
|---|---|---|
| NFR-02 `GET /products` p95 | < 200 ms | **Met** — 118 ms at the §4.2 load. Degrades to 2075 ms at 150 VUs, which is outside the NFR's stated load. |
| NFR-02 `POST /orders` p95 | < 800 ms | **Met** — 443 ms with the cache, 600 ms without. Note the margin is thin without the §5 fix. |
| NFR-03 read throughput | ≥ 50 req/s | **Met** — the stress ramp sustained ≈ 78 req/s on catalogue reads (97.9 req/s overall) at a 1.70 % error rate. |
| Error rate | < 1 % | **Met at load** (0.00 %), **missed under stress** (1.70 % at 150 VUs, which is the expected shape of a stress run). |

These verdicts hold **for this machine, at the §2.2 load**. The 20-VU figure NFR-02 actually names was not run; §4.2
used 3 ordering VUs. Re-run the canonical `k6/load-test.js` on hardware with ≥ 6 GB free to confirm at the stated load.

### Remaining risks

1. **The canonical load profile has still not been run.** §4.2 is a reduced stand-in (§2.2). The targets are met with
   room to spare, so the result is unlikely to invert, but it is not the measurement the Brief asks for.
2. **Per-stage stress numbers are missing** (§4.3) — needs a time-series export.
3. **Only products 1–4 are orderable.** `V1__init_inventory.sql` seeds stock for ids 1–4 while the catalogue seeds 10
   products, so ordering ids 5–10 returns `409 OUT_OF_STOCK`. Load scripts must stock them first or set `PRODUCT_IDS`.
   A related bug was found and fixed while preparing this run: stocking a product that had no row returned `500`
   (`StaleObjectStateException`), because a new `Stock` was built with `version=0`, which Spring Data treats as a
   detached row rather than a new one.
4. **The rate limiter was raised to 5000/s for every run here.** At its real setting (20/s, burst 40) a single client
   cannot reach these numbers — by design (FR-13). Any throughput claim must state which setting was in force.
5. **The circuit breaker is correct behaviour, not a defect.** Under stress the platform sheds load with `503` and
   creates no partial orders; `order 201 PENDING` stayed at 99 % while catalogue reads dropped to 98 %. Worth
   demonstrating deliberately in the G3 demo rather than hiding.
6. **Grafana and Prometheus were stopped** for these runs to free memory, so there are no dashboard screenshots to
   accompany these numbers. The Brief expects them for G3.

## 7. G4 re-run: canonical profile on adequate hardware

This section closes risks 1 and 2 above. Same scripts, **unchanged**, run at G4 on a machine that meets the Brief's
resource note, with the whole platform up (16 containers, Grafana and Prometheus included).

| Item | Value |
|---|---|
| Machine | Linux, 4 vCPU, 15 GB RAM, Docker 29.6, everything on one host |
| Commit | `c5116d9` (branch `capstone-integration-fixes`) — includes the §5 price cache, FR-14 tokens and JSON logs |
| Before the run | `scripts/verify-l0.sh` → `L0 GREEN`; `scripts/e2e-check.sh` → `E2E GREEN` (39/39) |
| Stock | products 1–4 set to 1 000 000 (`PUT /api/v1/inventory/{id}`), `PRODUCT_IDS=1,2,3,4` (risk 3) |
| Rate limit | raised to 5000/s for the run, restored afterwards (risk 4) |
| k6 | `grafana/k6:latest` on the compose network, `--out csv` for the stress time series |

### 7.1 Smoke

218 requests, **0 failed**, all checks passed (`token issued`, `list 200`, `item 200`, `order 201 PENDING`, `order readable`).

### 7.2 Load — the canonical `k6/load-test.js` (60 req/s browse + 20 ordering VUs, 5 min)

| Metric | Target | Result | Verdict |
|---|---|---|---|
| `GET /products` p95 | < 200 ms | **60.9 ms** | ✅ |
| `POST /orders` p95 at 20 VUs | < 800 ms | **181.6 ms** | ✅ |
| Catalogue read throughput | ≥ 50 req/s | **59.8 req/s** (the scenario's 60/s, held) | ✅ |
| Error rate | < 1 % | **0.00 %** (0 of 28 735) | ✅ |
| Dropped iterations | — | 1 | — |

All four thresholds in the script passed. Medians: 11.8 ms (products), 53.7 ms (orders).

### 7.3 Stress — `k6/stress-test.js`, ramp to 150 VUs over 11 min, per stage

| Stage | Throughput | `GET /products` p95 | `POST /orders` p95 | Errors |
|---|---|---|---|---|
| 0 → 50 VUs | 89.7 req/s | 85 ms | 126 ms | 0 |
| 50 → 100 VUs | 197.5 req/s | 408 ms | 465 ms | 0 |
| 100 → 150 VUs | 236.5 req/s | 734 ms | 797 ms | 0 |
| hold 150 VUs | 245.1 req/s | 892 ms | 983 ms | 0 |
| ramp down | 181.6 req/s | 600 ms | 661 ms | 0 |
| **Whole run** | **194.5 req/s** (128 427 requests) | 711 ms | 770 ms | **0.00 %** |

**Reading it.** Throughput grows with load up to ~100 VUs and then flattens at ~240 req/s while latency keeps rising:
that is the saturation point of this host, where 16 containers share 4 vCPUs. Past it the platform queues rather than
fails — not one error in 128 427 requests, every order `201 PENDING`, every check passed. The stress script's own
product threshold (`p95 < 500 ms`) is crossed from the 100 → 150 stage on; orders stay inside their `1500 ms`
threshold throughout. Catalogue reads degrade first because they share the gateway and the CPU with the Saga; the
next lever is a second gateway/product replica (Helm `replicaCount`), not code.

### 7.4 Verdicts at G4

| NFR | Target | Verdict |
|---|---|---|
| NFR-02 `GET /products` p95 | < 200 ms | **Met at the stated load** — 60.9 ms |
| NFR-02 `POST /orders` p95 at 20 VUs | < 800 ms | **Met at the stated load** — 181.6 ms |
| NFR-03 read throughput | ≥ 50 req/s | **Met** — 59.8 req/s held by the load profile; ~240 req/s total at saturation |
| Error rate | < 1 % | **Met under load and under stress** — 0.00 % in both |

Remaining risks 1 and 2 are closed by this section; 3 and 4 are stated above; 5 did not occur on this hardware (no
`503` under stress); 6 is closed (Grafana and Prometheus were running).

## 8. Findings from the `integration/merge-abc` runs (kept after the merge)

A second integration branch ran the same scripts on the integration laptop (12 logical CPUs, k6 on the same machine)
and fixed three bottlenecks. Two of the fixes are part of the merged code; the third belonged to an asynchronous
stock check that the merge replaced with the synchronous Retry → CircuitBreaker → Bulkhead chain of §5 (ADD Decision 5-4).

| # | Finding (evidence) | Root cause | Fix | In the merged branch |
|---|---|---|---|---|
| 1 | k6 sent 273 password logins for 224 orders in 30 s; Keycloak logged `user_temporarily_disabled` | the realm's brute-force protection disabled the shared test user, and every iteration retried the expensive password login | `k6/lib/auth.js`: `setup()` logs in once; VUs renew with the refresh-token grant | yes: 2 password logins per run, errors 0.93 % → 0 % |
| 2 | Zipkin restarted 15 times; its CPU bursts lined up with gateway p95 spikes (up to 1.4 s) | in-memory storage keeps 500 000 spans by default, more than the image's 160 MB heap at 100 % sampling | `MEM_MAX_SPANS=20000` in compose and the kind manifest | yes: 0 restarts, `GET /products` p95 951 → 50 ms |
| 3 | 70 % of orders got 503 at higher throughput (`JoinPointMatch was NOT bound`, then the breaker OPEN) | `@Retry` on a `CompletableFuture` stock check retried on another thread outside the Spring AOP proxy | `@Retry` moved to the synchronous caller | no longer applicable: the merged order-service calls inventory synchronously |

Result on that branch after the three fixes (5-minute `load-test.js`): `GET /products` p95 118 ms, `POST /orders` p95
223 ms, 59.6 catalogue req/s, 0.00 % errors. Stress to 150 VUs: 89 720 requests, 0 failed; throughput flattened at
≈ 150–160 req/s with api-gateway the busiest container (≈ 1.8 cores).

These numbers were measured on the two branches before the merge. Re-run smoke, load and stress on the merged branch
before quoting a final figure.

### 8.1 First run on the merged branch (2026-10-06): not a valid measurement

k6 ran from the `grafana/k6` container against the merged branch (fresh compose, gateway limit raised to 5000/s).
Smoke passed (190/190 checks, 0 failed requests). Load and stress returned **0 % errors**, but `GET /products` p95
1.29–1.47 s and `POST /orders` p95 1.36–1.5 s; a repeat load run also opened the inventory circuit breaker (12 % of
orders 503). This run is **not** quoted as a result: the host was starved. Windows showed 62 % CPU with the platform
idle and 0.8 GB of 15.7 GB RAM free, because Docker Desktop had been raised to 12 GB for kind. Zipkin traces showed
every service slow at trivial steps (66 ms to validate a token, ~700 ms in the gateway before forwarding a catalogue
read, Redis rate-limiter calls timing out at 500 ms while Redis itself was idle). A catalogue-only probe at 60 req/s
on the same setup gave p95 74–94 ms.

Re-run before G4 with Docker Desktop back at 8 GB (`.wslconfig`), other heavy applications closed, and k6 on the
host as in §7.
