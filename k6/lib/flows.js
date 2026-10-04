// User journeys shared by smoke, load and stress. Request names are the tags used by thresholds and the report.
import http from 'k6/http';
import { check } from 'k6';
import { authHeaders } from './auth.js';

export const GATEWAY = __ENV.GATEWAY_URL || 'http://localhost:8080';
const PRODUCT_IDS = (__ENV.PRODUCT_IDS || '1,2,3,4,5,6,7,8,9,10').split(',').map(Number);

function randomProductId() {
  return PRODUCT_IDS[Math.floor(Math.random() * PRODUCT_IDS.length)];
}

/** Public catalogue reads (FR-01, FR-03): no token. */
export function browseProducts() {
  const page = http.get(`${GATEWAY}/api/v1/products?page=0&size=20`, { tags: { name: 'GET /products' } });
  check(page, { 'list 200': (r) => r.status === 200 });

  const item = http.get(`${GATEWAY}/api/v1/products/${randomProductId()}`, { tags: { name: 'GET /products/{id}' } });
  check(item, { 'item 200': (r) => r.status === 200 });
}

/** Customer places an order (FR-05) and polls its status (FR-10). */
export function placeOrder() {
  const body = JSON.stringify({ items: [{ productId: randomProductId(), quantity: 1 }] });
  const created = http.post(`${GATEWAY}/api/v1/orders`, body, {
    headers: authHeaders(),
    tags: { name: 'POST /orders' },
    responseCallback: http.expectedStatuses(201),
  });
  if (!check(created, { 'order 201 PENDING': (r) => r.status === 201 && r.json('status') === 'PENDING' })) {
    return;
  }
  const status = http.get(`${GATEWAY}/api/v1/orders/${created.json('orderId')}`, {
    headers: authHeaders(),
    tags: { name: 'GET /orders/{id}' },
  });
  check(status, { 'order readable': (r) => r.status === 200 });
}

/** Orders need the Saga (L3). Before that, run catalogue-only with -e SKIP_ORDERS=true. */
export const ordersEnabled = __ENV.SKIP_ORDERS !== 'true';
