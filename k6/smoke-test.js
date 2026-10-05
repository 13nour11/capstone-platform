// Smoke: 1 VU for 1 minute. Proves every route answers before any load is applied.
//   k6 run -e K6_PASSWORD=<customer1 password> k6/smoke-test.js
import { sleep } from 'k6';
import { verifyLogin } from './lib/auth.js';
import { browseProducts, ordersEnabled, placeOrder, prepareOrders } from './lib/flows.js';

export const options = {
  vus: 1,
  duration: '1m',
  thresholds: {
    http_req_failed: ['rate<0.01'],
    checks: ['rate>0.99'],
  },
};

export function setup() {
  if (ordersEnabled) {
    verifyLogin();
    return prepareOrders();
  }
  return {};
}

export default function (data) {
  browseProducts();
  if (ordersEnabled) {
    placeOrder(data);
  }
  sleep(1);
}
