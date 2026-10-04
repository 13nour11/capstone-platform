// Keycloak password grant through the public client `api-gateway`. Never hard-code passwords: pass them with -e.
import http from 'k6/http';
import { check, fail } from 'k6';

const KEYCLOAK_URL = __ENV.KEYCLOAK_URL || 'http://localhost:8180';
const TOKEN_URL = `${KEYCLOAK_URL}/realms/ecommerce-platform/protocol/openid-connect/token`;
const REFRESH_MARGIN_MS = 30 * 1000;

// Module state is per VU: each VU keeps its own token and renews it before the 5-minute expiry.
let token = null;

function fetchToken() {
  if (!__ENV.K6_PASSWORD) {
    fail('K6_PASSWORD is required, e.g. k6 run -e K6_PASSWORD=... k6/smoke-test.js');
  }
  const res = http.post(TOKEN_URL, {
    grant_type: 'password',
    client_id: 'api-gateway',
    username: __ENV.K6_USERNAME || 'customer1',
    password: __ENV.K6_PASSWORD,
  }, { tags: { name: 'keycloak token' } });
  if (!check(res, { 'token issued': (r) => r.status === 200 })) {
    fail(`token request failed with HTTP ${res.status}`);
  }
  const body = res.json();
  return { value: body.access_token, expiresAt: Date.now() + body.expires_in * 1000 - REFRESH_MARGIN_MS };
}

/** Called from setup(): stops the run at once when the credentials are wrong. */
export function verifyLogin() {
  fetchToken();
}

export function authHeaders() {
  if (!token || Date.now() >= token.expiresAt) {
    token = fetchToken();
  }
  return { Authorization: `Bearer ${token.value}`, 'Content-Type': 'application/json' };
}
