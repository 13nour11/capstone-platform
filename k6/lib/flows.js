// User journeys shared by smoke, load and stress. Request names are the tags used by thresholds and the report.
import http from 'k6/http';
import { check, fail } from 'k6';
import { authHeaders, tokenFor } from './auth.js';

export const GATEWAY = __ENV.GATEWAY_URL || 'http://localhost:8080';
const PRODUCT_IDS = (__ENV.PRODUCT_IDS || '1,2,3,4,5,6,7,8,9,10').split(',').map(Number);
// Products ordered under load: inventory seeds stock for 1-3 (product 4 is the out-of-stock demo item)
const ORDER_PRODUCT_IDS = (__ENV.ORDER_PRODUCT_IDS || '1,2,3').split(',').map(Number);
const STOCK_TOP_UP = Number(__ENV.STOCK_TOP_UP || 1000000);

function randomProductId() {
  return PRODUCT_IDS[Math.floor(Math.random() * PRODUCT_IDS.length)];
}

function pick(ids) {
  return ids[Math.floor(Math.random() * ids.length)];
}

/**
 * Run once in setup(): reads the catalogue price of every product that will be ordered (the order API takes the
 * unit price) and, as ADMIN, tops up their stock so a long run measures latency, not an empty warehouse
 * (FR-12 PUT /inventory/{id}). Admin credentials: K6_ADMIN_USERNAME (default admin) / K6_ADMIN_PASSWORD
 * (default: K6_PASSWORD, the realm test users share one password).
 */
export function prepareOrders() {
  const admin = tokenFor(__ENV.K6_ADMIN_USERNAME || 'admin', __ENV.K6_ADMIN_PASSWORD || __ENV.K6_PASSWORD);
  const prices = {};
  for (const id of ORDER_PRODUCT_IDS) {
    const product = http.get(`${GATEWAY}/api/v1/products/${id}`, { tags: { name: 'setup' } });
    if (product.status !== 200) {
      fail(`product ${id}: HTTP ${product.status}`);
    }
    prices[id] = product.json('price');
    const stock = http.put(`${GATEWAY}/api/v1/inventory/${id}`, JSON.stringify({ availableQuantity: STOCK_TOP_UP }), {
      headers: { Authorization: `Bearer ${admin}`, 'Content-Type': 'application/json' },
      tags: { name: 'setup' },
    });
    if (stock.status !== 200) {
      fail(`stock top-up for product ${id}: HTTP ${stock.status}`);
    }
  }
  return { prices };
}

/** Public catalogue reads (FR-01, FR-03): no token. */
export function browseProducts() {
  const page = http.get(`${GATEWAY}/api/v1/products?page=0&size=20`, { tags: { name: 'GET /products' } });
  check(page, { 'list 200': (r) => r.status === 200 });

  const item = http.get(`${GATEWAY}/api/v1/products/${randomProductId()}`, { tags: { name: 'GET /products/{id}' } });
  check(item, { 'item 200': (r) => r.status === 200 });
}

/** Customer places an order (FR-05) and polls its status (FR-10). `data` comes from prepareOrders(). */
export function placeOrder(data) {
  const productId = pick(ORDER_PRODUCT_IDS);
  const body = JSON.stringify({ items: [{ productId, quantity: 1, unitPrice: data.prices[productId] }] });
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
