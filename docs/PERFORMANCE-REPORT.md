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
| Machine | Windows 11 laptop (integration run, 2026-10-05) — **not** the demo machine |
| Docker Desktop | 29.6, VM with 12 CPUs and 8 GB; the whole stack (21 containers) **and** k6 share it |
| Deployment under test | Docker Compose (`integration/merge-abc`, commit `a102c13`) |
| Commit | `a102c13` |
| Gateway rate limit during the run | 1000 req/s, burst 2000 (test setting, see below) |
| k6 version | `grafana/k6` image, latest on 2026-10-05 |

The gateway rate limiter (FR-13) works per client. All anonymous k6 traffic comes from one IP, so the limit is raised
for load runs. That is a test setting, not a production change, and is stated here so the numbers are honest.

## 3. Method

1. **Smoke** (`k6/smoke-test.js`, 1 VU, 1 min): every route answers, no errors.
2. **Baseline load** (`k6/load-test.js`, 5 min): record the numbers below **before** any change.
3. **Find the bottleneck**: Grafana (HTTP p95 per route, Hikari pool, JVM, Kafka lag) and Zipkin (slowest span) during the run.
4. **Fix one thing**, through a PR to the owner of that file (TEAM-GUIDE A9).
5. **Re-run the same load test** on the same machine and compare.
6. **Stress** (`k6/stress-test.js`, ramp to 150 VUs): record where latency and errors break.

```bash
k6 run -e K6_PASSWORD=... --summary-export k6/results/load-before.json k6/load-test.js
k6 run -e K6_PASSWORD=... --summary-export k6/results/load-after.json  k6/load-test.js
k6 run -e K6_PASSWORD=... --summary-export k6/results/stress.json      k6/stress-test.js
```

## 4. Results

### 4.1 Smoke

| Check | Result |
|---|---|
| Requests / errors | 229 requests, 0 failed |
| All checks passed | yes, 223/223 (all routes p95 44.8 ms) |

### 4.2 Load: before and after the fix

| Metric | Target | Before | After |
|---|---|---|---|
| `GET /products` p95 | < 200 ms | **872 ms (fail)** | 2615 ms (rejected change) |
| `GET /products` avg / median | — | 266 / 131 ms | 1221 ms avg |
| `POST /orders` p95 | < 800 ms | 787 ms (pass) | 4159 ms |
| Catalogue throughput | ≥ 50 req/s | 57.2 req/s (pass) | 38.0 req/s |
| Error rate | < 1 % | 0.93 % (pass) | 4.42 % |

"Before" is the baseline on the services as built (`load-before.json`, 25 138 requests). "After" is the experiment
in §5, which made things worse and was **not** kept; the fix that meets the GET target is still open.

### 4.3 Stress

| VUs | `GET /products` p95 | `POST /orders` p95 | Error rate | First thing to saturate |
|---|---|---|---|---|
| 50 | — | — | — | — |
| 100 | — | — | — | — |
| 150 | — | — | — | — |

*Not run on the integration machine:* at 20 VUs its CPU was already ~92 % busy, so a 150-VU stress run there would
measure the laptop, not the platform. Run it on the demo machine.

## 5. Bottleneck found and fixed

| | |
|---|---|
| Symptom | `GET /api/v1/products` p95 872 ms at 60 req/s + 20 ordering VUs (target 200 ms); everything else passed |
| Evidence | Prometheus over the run: product-service answers the list in p95 92 ms (items 13 ms), the gateway's own GET p95 is 680 ms → ~590 ms is added at the gateway hop. Docker VM `system_cpu_usage` ≈ 0.92. JVM GC pause totals: review 21.5 s, order 14.5 s, gateway 9.1 s (Serial GC). |
| Hypothesis tested | the image's startup-oriented JVM flags (`-XX:TieredStopAtLevel=1`, C1 only) cost throughput under load |
| Experiment | same images with C2 enabled (`JAVA_TOOL_OPTIONS` override, Serial GC kept), 1-min warm-up, same 5-min load |
| Result | **rejected**: every metric got worse (GET p95 2615 ms, POST p95 4159 ms, 38 req/s, 4.4 % errors) — C2 compile threads compete for the already saturated CPU. The Dockerfile flags stay as they are. |
| Status | **Open for the team** — the measured constraint is the shared, CPU-saturated laptop VM. Next candidates, one at a time on the demo machine: give the gateway more CPU/heap (or a second replica), drop unused scrape duplication (`services-on-host` job also hits the containers), and check whether the per-request Redis rate-limit call dominates the gateway time (Zipkin span breakdown). |

Where to look first (plan §4 L5): Hikari pool size vs Tomcat threads, a missing index on
`orders(customer_id, created_at)`, N+1 queries (the product list already uses one projection query), cache
serialization, Feign bulkhead limits, and the gateway rate limiter throttling the test itself.

## 6. Conclusions and remaining risks

- On the integration laptop the platform meets the order latency, throughput and error-rate targets but **not**
  the cached catalogue p95 (872 ms vs 200 ms). The latency is added at the gateway hop while the VM's CPU is
  saturated, not in product-service or the cache.
- One hypothesis (JIT flags) was measured and rejected; no bottleneck fix is claimed yet.
- Risk: numbers from a laptop that also runs k6 and 20 other containers are pessimistic; the Brief's G3 evidence
  must come from the team's own runs on the demo machine.
