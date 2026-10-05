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
| Machine | — (CPU, RAM, OS) |
| Docker Desktop memory | — |
| Deployment under test | Docker Compose / kind — |
| Commit | — (`git rev-parse --short HEAD`) |
| Gateway rate limit during the run | — (`GATEWAY_RATE_LIMIT_REPLENISH_RATE` / `BURST_CAPACITY`) |
| k6 version | — |

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
| Requests / errors | — |
| All checks passed | — |

### 4.2 Load: before and after the fix

| Metric | Target | Before | After |
|---|---|---|---|
| `GET /products` p95 | < 200 ms | — | — |
| `GET /products/{id}` p95 | — | — | — |
| `POST /orders` p95 | < 800 ms | — | — |
| Catalogue throughput | ≥ 50 req/s | — | — |
| Error rate | < 1 % | — | — |

### 4.3 Stress

| VUs | `GET /products` p95 | `POST /orders` p95 | Error rate | First thing to saturate |
|---|---|---|---|---|
| 50 | — | — | — | — |
| 100 | — | — | — | — |
| 150 | — | — | — | — |

## 5. Bottleneck found and fixed

| | |
|---|---|
| Symptom | — (which metric, at what load) |
| Evidence | — (Grafana panel / Zipkin trace screenshot in `docs/architecture/`) |
| Root cause | — |
| Fix | — (PR link) |
| Effect | — (before → after, from §4.2) |

Where to look first (plan §4 L5): Hikari pool size vs Tomcat threads, a missing index on
`orders(customer_id, created_at)`, N+1 queries (the product list already uses one projection query), cache
serialization, Feign bulkhead limits, and the gateway rate limiter throttling the test itself.

## 6. Conclusions and remaining risks

—
