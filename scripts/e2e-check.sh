#!/usr/bin/env bash
# End-to-end acceptance check against the running Compose platform: every functional requirement and
# the NFRs that can be shown live (Brief §3), through the gateway, with real Keycloak tokens.
#
#   docker compose -f deployment/docker/docker-compose.yml up -d     # then wait for scripts/verify-l0.sh
#   scripts/e2e-check.sh
#
# It changes state the way a demo would: it places orders, toggles the payment failure switch (and
# restores it), stops and starts payment-service once, and puts one unreadable record on a DLT.
# Needs: bash, curl, docker compose. Reads the passwords from deployment/docker/.env.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COMPOSE_FILE="$ROOT/deployment/docker/docker-compose.yml"
ENV_FILE="${ENV_FILE:-$ROOT/deployment/docker/.env}"
GW="${GW:-http://localhost:8080}"
KC="${KC:-http://localhost:8180/realms/ecommerce-platform/protocol/openid-connect/token}"
SAGA_TIMEOUT="${SAGA_TIMEOUT:-60}"

[[ -f "$ENV_FILE" ]] || { echo "$ENV_FILE not found"; exit 2; }
set -a
# shellcheck disable=SC1090
source <(sed -e 's/\r$//' "$ENV_FILE")
set +a

failures=0
passed=0
pass() { printf '  \033[32m✓\033[0m %s\n' "$1"; passed=$((passed + 1)); }
fail() { printf '  \033[31m✗\033[0m %s\n' "$1"; failures=$((failures + 1)); }
check() { if eval "$2"; then pass "$1"; else fail "$1"; fi; }
section() { printf '\n%s\n' "$1"; }
compose() { docker compose -f "$COMPOSE_FILE" "$@"; }

# Extracts a top-level JSON string/number field without needing jq.
json() { sed -nE "s/.*\"$1\":\"?([^\",}]*)\"?.*/\1/p" | head -n 1; }

token() {
  curl -s "$KC" -d grant_type=password -d client_id=api-gateway -d username="$1" \
       -d password="$KC_TEST_USER_PASSWORD" | json access_token
}

# status code of a request: code METHOD URL [TOKEN] [JSON BODY]
code() {
  local auth=()
  [[ -n "${3:-}" ]] && auth=(-H "Authorization: Bearer $3")
  curl -s -o /dev/null -w '%{http_code}' -X "$1" "${auth[@]}" -H 'Content-Type: application/json' \
       ${4:+-d "$4"} "$2"
}

place_order() { # place_order TOKEN PRODUCT QTY -> response body
  curl -s -X POST "$GW/api/v1/orders" -H "Authorization: Bearer $1" -H 'Content-Type: application/json' \
       -d "{\"items\":[{\"productId\":$2,\"quantity\":$3}]}"
}

order_status() { curl -s "$GW/api/v1/orders/$1" -H "Authorization: Bearer $CUSTOMER" | json status; }

await_status() { # await_status ORDER_ID EXPECTED [SECONDS]
  local deadline=$((SECONDS + ${3:-$SAGA_TIMEOUT}))
  while (( SECONDS < deadline )); do
    [[ "$(order_status "$1")" == "$2" ]] && return 0
    sleep 1
  done
  return 1
}

available() { curl -s "$GW/api/v1/inventory/$1" -H "Authorization: Bearer $ADMIN" | json available; }

set_payment_failure_rate() {
  PAYMENT_FAILURE_RATE="$1" compose up -d --no-build payment-service >/dev/null 2>&1
  local deadline=$((SECONDS + 180))
  until [[ "$(docker inspect --format '{{.State.Health.Status}}' "$(compose ps -q payment-service)")" == healthy ]]; do
    (( SECONDS > deadline )) && return 1
    sleep 3
  done
  sleep 5 # let the consumer join its group
}

echo "=========================================================="
echo " End-to-end check against $GW"
echo "=========================================================="

ADMIN="$(token admin)"
CUSTOMER="$(token customer1)"
OTHER="$(token customer2)"
[[ -n "$ADMIN" && -n "$CUSTOMER" && -n "$OTHER" ]] || { echo "Could not get tokens from Keycloak ($KC)"; exit 2; }

# After a (re)start the gateway's load balancer needs one Eureka refresh (~30 s) to see new instances.
warm_deadline=$((SECONDS + 120))
until [[ $(code GET "$GW/api/v1/orders" "$CUSTOMER") == 200 && $(code GET "$GW/api/v1/inventory/1" "$ADMIN") == 200 ]]; do
  (( SECONDS > warm_deadline )) && { echo "The gateway cannot reach order-service / inventory-service"; exit 2; }
  sleep 3
done

# ------------------------------------------------------------------ catalogue and security
section "Catalogue and security (FR-01 … FR-04, FR-13, FR-15)"
check "FR-01 GET /api/v1/products without a token -> 200" '[[ $(code GET "$GW/api/v1/products?page=0&size=5") == 200 ]]'
check "FR-03 product detail carries categoryName" 'curl -s "$GW/api/v1/products/1" | grep -q "\"categoryName\""'
check "FR-04 POST /api/v1/products without a token -> 401" '[[ $(code POST "$GW/api/v1/products" "" "{}") == 401 ]]'
check "FR-04 invalid token -> 401" '[[ $(code GET "$GW/api/v1/orders" "not-a-jwt") == 401 ]]'
check "FR-02 CUSTOMER creating a product -> 403" '[[ $(code POST "$GW/api/v1/products" "$CUSTOMER" "{\"name\":\"x\",\"price\":1,\"categoryId\":1}") == 403 ]]'
NEW_PRODUCT='{"name":"E2E lamp","description":"e2e","price":12.50,"categoryId":1}'
created="$(curl -s -X POST "$GW/api/v1/products" -H "Authorization: Bearer $ADMIN" -H 'Content-Type: application/json' -d "$NEW_PRODUCT")"
PID="$(json id <<<"$created")"
check "FR-02 ADMIN creates a product -> 201 with id" '[[ -n "$PID" ]]'
curl -s "$GW/api/v1/products/$PID" >/dev/null
check "FR-15 the read is cached in Redis" 'compose exec -T redis redis-cli --scan --pattern "*" | grep -q "$PID"'
check "FR-02 ADMIN updates it -> 200" '[[ $(code PUT "$GW/api/v1/products/$PID" "$ADMIN" "${NEW_PRODUCT/12.50/13.00}") == 200 ]]'
check "FR-15 the write evicted the cached item" '! compose exec -T redis redis-cli --scan --pattern "*" | grep -q ":$PID\$"'
check "FR-02 ADMIN deletes it -> 204" '[[ $(code DELETE "$GW/api/v1/products/$PID" "$ADMIN") == 204 ]]'
check "Internal /api/v1/inventory/check is not routed by the gateway -> 403" '[[ $(code GET "$GW/api/v1/inventory/check?productId=1&quantity=1" "$ADMIN") == 403 ]]'
codes="$(seq 1 120 | xargs -P 60 -I{} curl -s -o /dev/null -w '%{http_code}\n' "$GW/api/v1/products/1")"
check "FR-13 a burst of 120 requests from one client is rate limited (429)" 'grep -q 429 <<<"$codes"'

# ------------------------------------------------------------------ happy path
section "Order happy path (FR-05 … FR-10, FR-11)"
before="$(available 1)"
resp="$(place_order "$CUSTOMER" 1 2)"
OID="$(json orderId <<<"$resp")"
check "FR-05 POST /api/v1/orders -> 201 with orderId and PENDING" '[[ -n "$OID" && "$(json status <<<"$resp")" == PENDING ]]'
check "FR-09 the Saga confirms the order (CONFIRMED)" 'await_status "$OID" CONFIRMED'
check "FR-07 stock went down by the quantity ($before -> $((before - 2)))" '[[ $(available 1) == $((before - 2)) ]]'
check "FR-10 the customer lists own orders" 'curl -s "$GW/api/v1/orders" -H "Authorization: Bearer $CUSTOMER" | grep -q "$OID"'
check "FR-10 another customer cannot read it -> 404" '[[ $(code GET "$GW/api/v1/orders/$OID" "$OTHER") == 404 ]]'
check "FR-11 the confirmation was sent" 'sleep 2; compose logs notification-service 2>/dev/null | grep -q "orderId=$OID: your order is CONFIRMED"'
check "FR-12 ADMIN reads stock -> 200, CUSTOMER -> 403" '[[ $(code GET "$GW/api/v1/inventory/1" "$ADMIN") == 200 && $(code GET "$GW/api/v1/inventory/1" "$CUSTOMER") == 403 ]]'

section "No stock (FR-06)"
before="$(available 1)"
resp_code="$(code POST "$GW/api/v1/orders" "$CUSTOMER" '{"items":[{"productId":1,"quantity":100000}]}')"
check "FR-06 more than the stock -> 409 OUT_OF_STOCK" '[[ $resp_code == 409 ]]'
check "FR-06 stock unchanged, nothing reserved" '[[ $(available 1) == "$before" ]]'

# ------------------------------------------------------------------ idempotent payment
section "Payment exactly once (FR-08)"
KEY="e2e-$RANDOM-$RANDOM"
BODY="{\"orderId\":\"e2e-$KEY\",\"amount\":10.00}"
first="$(curl -s -D - -X POST "$GW/api/v1/payments" -H "Authorization: Bearer $ADMIN" -H "Idempotency-Key: $KEY" -H 'Content-Type: application/json' -d "$BODY")"
second="$(curl -s -D - -X POST "$GW/api/v1/payments" -H "Authorization: Bearer $ADMIN" -H "Idempotency-Key: $KEY" -H 'Content-Type: application/json' -d "$BODY")"
check "FR-08 a retried request returns the same payment, marked as a replay" '[[ "$(json id <<<"$first")" == "$(json id <<<"$second")" ]] && grep -qi "Idempotent-Replayed: true" <<<"$second"'
check "FR-08 same key with a different body -> 422" '[[ $(curl -s -o /dev/null -w "%{http_code}" -X POST "$GW/api/v1/payments" -H "Authorization: Bearer $ADMIN" -H "Idempotency-Key: $KEY" -H "Content-Type: application/json" -d "{\"orderId\":\"e2e-$KEY\",\"amount\":99.00}") == 422 ]]'

# ------------------------------------------------------------------ compensation
section "Payment fails -> compensation (FR-09, FR-11, NFR-05)"
if set_payment_failure_rate 1.0; then
  before="$(available 2)"
  OID2="$(place_order "$CUSTOMER" 2 3 | json orderId)"
  check "FR-09 a declined payment cancels the order (CANCELLED)" 'await_status "$OID2" CANCELLED'
  check "FR-07 the reserved stock is released (back to $before)" 'for i in $(seq 1 20); do [[ $(available 2) == "$before" ]] && break; sleep 1; done; [[ $(available 2) == "$before" ]]'
  check "FR-11 the cancellation notice was sent" 'sleep 2; compose logs notification-service 2>/dev/null | grep -q "orderId=$OID2: your order is CANCELLED"'
else
  fail "payment-service did not come back healthy with the failure switch on"
fi
set_payment_failure_rate 0.0 || fail "payment-service did not come back healthy with the failure switch off"
nfr05="$(compose exec -T postgres psql -U "${POSTGRES_USER:-postgres}" -d inventory_db -tA -c \
  "SELECT count(*) FROM reservation r WHERE r.status = 'RESERVED' AND r.order_id IN (SELECT order_id FROM cancelled_order) AND r.created_at < now() - interval '30 seconds';")"
check "NFR-05 no RESERVED stock older than 30 s for a cancelled order (query = $nfr05)" '[[ "$nfr05" == 0 ]]'

# ------------------------------------------------------------------ chaos
section "Payment down (NFR-01)"
compose stop payment-service >/dev/null 2>&1
OID3="$(place_order "$CUSTOMER" 3 1 | json orderId)"
sleep 8
check "NFR-01 with payment down the order is accepted and stays PENDING" '[[ -n "$OID3" && "$(order_status "$OID3")" == PENDING ]]'
compose start payment-service >/dev/null 2>&1
check "NFR-01 when payment returns the same order completes (CONFIRMED)" 'await_status "$OID3" CONFIRMED 180'

# ------------------------------------------------------------------ messaging
section "Messaging (NFR-10)"
# A PaymentFailed that cannot be parsed: order-service and inventory-service both consume payment-events.
compose exec -T kafka bash -c "printf 'eventType:PaymentFailed\te2e-poison|{not json\n' | kafka-console-producer --bootstrap-server kafka:29092 --topic payment-events --property parse.key=true --property key.separator='|' --property parse.headers=true >/dev/null 2>&1"
sleep 12
dlt="$(compose exec -T kafka kafka-console-consumer --bootstrap-server kafka:29092 --topic payment-events.DLT \
  --from-beginning --timeout-ms 8000 --property print.key=true --property print.headers=true 2>/dev/null | tr -d '\000')"
check "NFR-10 an unreadable record is parked on payment-events.DLT" 'grep -q "e2e-poison" <<<"$dlt"'
check "NFR-10 order-service and inventory-service both parked it (DLT names the consumer group)" 'grep -q "order-service" <<<"$dlt" && grep -q "inventory-service" <<<"$dlt"'

# ------------------------------------------------------------------ service-to-service
section "Service-to-service (FR-14)"
check "FR-14 inventory /check without a token -> 401 (direct call, bypassing the gateway)" '[[ $(code GET "http://localhost:8084/api/v1/inventory/check?productId=1&quantity=1") == 401 ]]'
check "FR-14 a customer token is not enough -> 403" '[[ $(code GET "http://localhost:8084/api/v1/inventory/check?productId=1&quantity=1" "$CUSTOMER") == 403 ]]'
check "FR-14 order-service passes the check with its own SERVICE token (orders above succeeded)" '[[ -n "$OID" ]]'

# ------------------------------------------------------------------ observability
section "Observability (NFR-06)"
TRACE="$(compose logs order-service 2>/dev/null | grep "orderId=$OID" | sed -nE 's/.*"traceId":"([0-9a-f]+)".*/\1/p' | head -n 1)"
check "NFR-06 logs are JSON with a traceId" '[[ -n "$TRACE" ]]'
spans="$(curl -s "http://localhost:9411/api/v2/trace/$TRACE")"
services="$(grep -o '"serviceName":"[^"]*"' <<<"$spans" | sort -u | tr '\n' ' ')"
trace_has_all() {
  local s
  for s in api-gateway order-service inventory-service payment-service notification-service; do
    grep -q "\"serviceName\":\"$s\"" <<<"$spans" || return 1
  done
}
check "NFR-06 one trace spans gateway, order, inventory, payment and notification ($services)" 'trace_has_all'
check "Prometheus scrapes every service" '[[ $(curl -s "http://localhost:9090/api/v1/targets?state=active" | grep -o "\"health\":\"up\"" | wc -l) -ge 8 ]]'

# ------------------------------------------------------------------ bonus
section "Bonus B2 (FR-16)"
summary="$(curl -s "$GW/api/v1/analytics/summary?hours=24" -H "Authorization: Bearer $ADMIN")"
check "FR-16 ADMIN reads the analytics summary with confirmed and cancelled orders" 'grep -q "\"confirmed\":[1-9]" <<<"$summary" && grep -q "\"cancelled\":[1-9]" <<<"$summary"'
check "FR-16 CUSTOMER -> 403" '[[ $(code GET "$GW/api/v1/analytics/summary" "$CUSTOMER") == 403 ]]'

echo
echo "=========================================================="
if (( failures == 0 )); then
  echo " E2E GREEN: $passed checks passed"
else
  echo " E2E RED: $failures of $((passed + failures)) checks failed"
fi
echo "=========================================================="
(( failures == 0 ))
