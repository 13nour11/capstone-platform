// Keycloak password grant through the public client `api-gateway`. Never hard-code passwords: pass them with -e.
import http from 'k6/http';
import { check, fail } from 'k6';

const KEYCLOAK_URL = __ENV.KEYCLOAK_URL || 'http://localhost:8180';
const TOKEN_URL = `${KEYCLOAK_URL}/realms/ecommerce-platform/protocol/openid-connect/token`;
const REFRESH_MARGIN_MS = 30 * 1000;

// Module state is per VU: each VU starts from the token setup() fetched and renews it with the refresh token.
// Only setup() logs in with the password: the realm is brute-force protected, and 20+ VUs logging in as the same
// user within a second get it temporarily disabled (401), which made every iteration retry an expensive login.
let token = null;

function session(body) {
  return { value: body.access_token, refresh: body.refresh_token, expiresAt: Date.now() + body.expires_in * 1000 - REFRESH_MARGIN_MS };
}

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
  return session(res.json());
}

function refreshToken(current) {
  const res = http.post(TOKEN_URL, {
    grant_type: 'refresh_token',
    client_id: 'api-gateway',
    refresh_token: current.refresh,
  }, { tags: { name: 'keycloak refresh' } });
  if (!check(res, { 'token refreshed': (r) => r.status === 200 })) {
    fail(`token refresh failed with HTTP ${res.status}`);
  }
  return session(res.json());
}

/** One-off token for setup() as another user (e.g. admin for the stock top-up). */
export function tokenFor(username, password) {
  const res = http.post(TOKEN_URL, {
    grant_type: 'password',
    client_id: 'api-gateway',
    username,
    password,
  }, { tags: { name: 'keycloak token' } });
  if (!check(res, { 'token issued': (r) => r.status === 200 })) {
    fail(`token request for ${username} failed with HTTP ${res.status}`);
  }
  return res.json().access_token;
}

/** Called from setup(): the one password login of the run (stops it at once when the credentials are wrong). */
export function verifyLogin() {
  return fetchToken();
}

/** `loggedIn` is the session returned by setup(); each VU refreshes its own copy before expiry. */
export function authHeaders(loggedIn) {
  if (!token) {
    token = loggedIn;
  }
  if (Date.now() >= token.expiresAt) {
    token = refreshToken(token);
  }
  return { Authorization: `Bearer ${token.value}`, 'Content-Type': 'application/json' };
}
