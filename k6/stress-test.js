// Stress: ramp to 150 VUs to find where the platform breaks (latency knee, errors, pool exhaustion).
// Thresholds do not abort the run: the point is to record the breaking point for the Performance Report.
//   k6 run -e K6_PASSWORD=<customer1 password> --summary-export k6/results/stress.json k6/stress-test.js
import { sleep } from 'k6';
import { verifyLogin } from './lib/auth.js';
import { browseProducts, ordersEnabled, placeOrder } from './lib/flows.js';

const ORDER_SHARE = 0.2;

export const options = {
  stages: [
    { duration: '2m', target: 50 },
    { duration: '3m', target: 100 },
    { duration: '3m', target: 150 },
    { duration: '2m', target: 150 },
    { duration: '1m', target: 0 },
  ],
  thresholds: {
    http_req_failed: [{ threshold: 'rate<0.05', abortOnFail: false }],
    'http_req_duration{name:GET /products}': [{ threshold: 'p(95)<500', abortOnFail: false }],
    'http_req_duration{name:POST /orders}': [{ threshold: 'p(95)<1500', abortOnFail: false }],
  },
};

export function setup() {
  if (ordersEnabled) {
    verifyLogin();
  }
}

export default function () {
  if (ordersEnabled && Math.random() < ORDER_SHARE) {
    placeOrder();
  } else {
    browseProducts();
  }
  sleep(0.5);
}
