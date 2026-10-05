# Performance Report (Layer L5, Gate G3)

> Owner: A. Every number in this report comes from a real k6 run on the machine described in §2.
> Cells marked `—` are filled after the run; never estimate them.

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
| Machine | Windows 11 laptop, Intel Core i5-1335U (2 performance + 8 efficiency cores, 15 W), 16 GB — **not** the demo machine |
| Docker Desktop | 29.6, VM with 12 vCPUs and 8 GB; the whole stack (21 containers) **and** k6 share it |
| Deployment under test | Docker Compose, branch `integration/merge-abc` (baseline at `a102c13`, final at `0140f89`) |
| Gateway rate limit during the runs | 1000 req/s, burst 2000 (test setting, see below) |
| k6 version | `grafana/k6` image, latest on 2026-10-05 |

The gateway rate limiter (FR-13) works per client. All anonymous k6 traffic comes from one IP, so the limit is raised
for load runs. That is a test setting, not a production change, and is stated here so the numbers are honest.

## 3. Method

1. **Smoke** (`k6/smoke-test.js`, 1 VU, 1 min): every route answers, no errors.
2. **Baseline load** (`k6/load-test.js`, 5 min): record the numbers **before** any change.
3. **Find the bottleneck** with short controlled probes (same request mix, one variable at a time), Prometheus
   (server-side latency per service, CPU, GC, Resilience4j) and container CPU sampled every 10 s.
4. **Fix one thing at a time** and re-run the same 5-minute load test after each fix.
5. **Stress** (`k6/stress-test.js`, ramp to 150 VUs): record where latency and errors break.

```bash
k6 run -e K6_PASSWORD=... --summary-export k6/results/load.json k6/load-test.js
k6 run -e K6_PASSWORD=... --summary-export k6/results/stress.json --out csv=k6/results/stress.csv k6/stress-test.js
```

## 4. Results

### 4.1 Smoke

| Check | Result |
|---|---|
| Requests / errors | 229 requests, 0 failed |
| All checks passed | yes, 223/223 (all routes p95 44.8 ms) |

### 4.2 Load: before and after the fixes (same 5-minute `load-test.js`, same machine)

| Metric | Target | Before | After fix 1 (k6 login) | After fix 2 (Zipkin) | **After fix 3 (retry) = final** |
|---|---|---|---|---|---|
| `GET /products` p95 | < 200 ms | 872 ms ✗ | 951 ms ✗ | 50 ms | **118 ms ✓** |
| `GET /products` avg | — | 266 ms | 281 ms | 20 ms | **40 ms** |
| `POST /orders` p95 | < 800 ms | 787 ms | 821 ms ✗ | 527 ms | **223 ms ✓** |
| Catalogue throughput | ≥ 50 req/s | 57.2 req/s | 57.5 req/s | 59.7 req/s | **59.6 req/s ✓** |
| Error rate | < 1 % | 0.93 % | 0.00 % | 12.72 % ✗ | **0.00 % ✓** |
| Requests in 5 min | — | 25 138 | 25 623 | 23 547 | **28 686** |
| k6 thresholds | all | crossed | crossed | crossed | **all passed (exit 0)** |

Fix 2 removed the latency bottleneck; at the higher throughput it exposed a resilience bug (12.7 % of orders got
503), which fix 3 corrected. All three are in §5.

### 4.3 Stress (`stress-test.js`, 11 min, ramp to 150 VUs, 20 % of iterations place an order)

| Stage | `GET /products` p95 | `GET /products/{id}` p95 | `POST /orders` p95 | Throughput | Errors |
|---|---|---|---|---|---|
| ramp 0 → 50 VUs | 200 ms | 184 ms | 234 ms | 80.6 req/s | 0 % |
| ramp 50 → 100 VUs | 713 ms | 656 ms | 611 ms | 142.8 req/s | 0 % |
| ramp 100 → 150 VUs | 1236 ms | 1149 ms | 985 ms | 149.2 req/s | 0 % |
| hold 150 VUs | 1349 ms | 1258 ms | 1025 ms | 162.5 req/s | 0 % |
| ramp 150 → 0 VUs | 936 ms | 847 ms | 821 ms | 128.5 req/s | 0 % |

Totals: 89 720 requests, **0 failed**, no container restarted or was OOM-killed (Zipkin included).

**Breaking point.** Throughput stops growing at about 150–160 req/s once load passes roughly 70–100 VUs; beyond
that, latency grows instead (requests queue), but nothing fails. The script's stress threshold
`GET /products p95 < 500 ms` is crossed from the 50 → 100 stage on.

**First thing to saturate.** During the 150-VU hold the busiest container is **api-gateway (≈ 1.8 cores)**, then
product-service (≈ 1.0), order-service (≈ 0.9) and inventory-service (≈ 0.8); all containers together use ≈ 6.9
cores of the laptop's 12 logical CPUs, while k6 runs on the same machine. Memory stayed below 66 % of every
container limit. The next lever would be a second gateway replica (Helm `replicaCount`) on a machine that has the
cores for it.

## 5. Bottlenecks found and fixed

The baseline symptom was `GET /api/v1/products` p95 872 ms (target 200 ms). Controlled probes showed that the
catalogue path itself was fast: catalogue-only traffic through the gateway had p95 **39 ms**; adding the 20
ordering VUs pushed it to 444 ms with Keycloak at 76 % CPU — in an order-only probe Keycloak reached 642 % CPU.

| # | Finding (evidence) | Root cause | Fix | Effect |
|---|---|---|---|---|
| 1 | k6 sent **273** password logins for 224 orders in 30 s; Keycloak log `LOGIN_ERROR … user_temporarily_disabled` | The realm is brute-force protected; 20 VUs logging in as the same user within a second got the user temporarily disabled (401), and every k6 iteration retried the expensive password login | `k6/lib/auth.js`: `setup()` logs in once; VUs reuse that token and renew it with the refresh-token grant. Brute-force protection stays on. | 2 password logins per run; errors 0.93 % → 0 %. Latency unchanged — not the main cost. |
| 2 | Zipkin had **restarted 15 times**; its CPU bursts (150–230 %) lined up with the gateway p95 spikes (up to 1.4 s per 30 s window) | Zipkin's in-memory storage keeps 500 000 spans by default; the image runs with a 160 MB heap and `ExitOnOutOfMemoryError`, so with 100 % sampling it filled the heap, died and restarted, and each JVM restart burst starved the gateway | `MEM_MAX_SPANS=20000` for Zipkin in compose and in the kind manifest | 0 restarts; `GET /products` p95 951 → 50 ms |
| 3 | At the higher throughput, 70 % of orders got 503: log `IllegalStateException: Required to bind 2 arguments… (JoinPointMatch was NOT bound)` 121×, then `CircuitBreaker 'inventoryService' is OPEN` 5 799× | `@Retry` on the `CompletableFuture` stock check retried asynchronously on another thread, where Spring AOP cannot re-run the advice chain; every retry failed and opened the circuit breaker | `@Retry` moved to the synchronous caller (`InventoryServiceClient`), so `Retry(CircuitBreaker(TimeLimiter(Bulkhead)))` still holds and each attempt re-enters the proxy; out-of-stock answers are not retried. New test `shouldAcceptOrder_whenInventoryFailsOnceThenRecovers`. | errors 12.7 % → 0 %; `POST /orders` p95 223 ms |

**Measured and rejected** (kept for the record): re-enabling the C2 JIT (`TieredStopAtLevel=1` removed) on every
service made everything worse while the login storm was still running (GET p95 2615 ms); on the gateway alone
it later helped (5-min probe p95 654 → 289 ms) but did not meet the target by itself, so the image flags were left
unchanged. Trace sampling at 10 % lowered the probe p95 to 368 ms but was not needed once Zipkin was fixed, so
sampling stays at 1.0 (NFR-06).

## 6. Conclusions and remaining risks

- After the three fixes every NFR-02/03 target is met on the integration laptop: `GET /products` p95 118 ms,
  `POST /orders` p95 223 ms at 20 VUs, 59.6 catalogue req/s, 0 % errors.
- Under stress the platform degrades gracefully (latency, not errors) and saturates at ≈ 150–160 req/s on this
  laptop, with the gateway as the first saturated component.
- Risk: all runs share one 15 W laptop with k6; the demo machine will give different (likely better) numbers and
  the team should re-run the three scripts there for the G3 evidence.
