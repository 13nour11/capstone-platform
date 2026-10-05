// Load: the NFR-02/03 targets for 5 minutes.
//   browse: 60 catalogue requests/s (target >= 50 req/s, GET /products p95 < 200 ms)
//   orders: 20 VUs placing orders (POST /orders p95 < 800 ms)
// Raise the gateway rate limit for the run (GATEWAY_RATE_LIMIT_REPLENISH_RATE), otherwise k6 measures 429s.
//   k6 run -e K6_PASSWORD=<customer1 password> --summary-export k6/results/load.json k6/load-test.js
import http from 'k6/http';
import { check, sleep } from 'k6';
import { verifyLogin } from './lib/auth.js';
import { GATEWAY, ordersEnabled, placeOrder } from './lib/flows.js';

const scenarios = {
  browse: {
    executor: 'constant-arrival-rate',
    exec: 'browse',
    rate: 60,
    timeUnit: '1s',
    duration: '5m',
    preAllocatedVUs: 20,
    maxVUs: 60,
  },
};
if (ordersEnabled) {
  scenarios.orders = { executor: 'constant-vus', exec: 'order', vus: 20, duration: '5m' };
}

export const options = {
  scenarios,
  thresholds: {
    'http_req_duration{name:GET /products}': ['p(95)<200'],
    'http_req_duration{name:POST /orders}': ['p(95)<800'],
    'http_reqs{scenario:browse}': ['rate>=50'],
    http_req_failed: ['rate<0.01'],
  },
};

export function setup() {
  if (ordersEnabled) {
    verifyLogin();
  }
}

export function browse() {
  const res = http.get(`${GATEWAY}/api/v1/products?page=0&size=20`, { tags: { name: 'GET /products' } });
  check(res, { 'list 200': (r) => r.status === 200 });
}

export function order() {
  placeOrder();
  sleep(1);
}
