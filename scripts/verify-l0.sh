#!/usr/bin/env bash
# L0 Definition of Done (Brief §6): `docker compose up` -> all infrastructure healthy, config-server serves
# config-repo, eureka-server is up and every running platform service is registered in it.
#
# Usage (from the repo root, after `docker compose -f deployment/docker/docker-compose.yml up -d`):
#   scripts/verify-l0.sh
# Options (environment variables):
#   WAIT_SECONDS=240        how long to wait for health / registration (Keycloak alone needs ~60 s)
#   SKIP_INFRA=1            skip the container checks (services started with `mvn spring-boot:run`)
#   EXPECTED_APPS="..."     Eureka apps that must be UP; default: every platform service container that is running
#   CONFIG_URL / EUREKA_URL override http://localhost:8888 / http://localhost:8761
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COMPOSE_FILE="${COMPOSE_FILE:-$ROOT/deployment/docker/docker-compose.yml}"
CONFIG_URL="${CONFIG_URL:-http://localhost:8888}"
EUREKA_URL="${EUREKA_URL:-http://localhost:8761}"
WAIT_SECONDS="${WAIT_SECONDS:-240}"
SKIP_INFRA="${SKIP_INFRA:-0}"

INFRA=(postgres zookeeper kafka redis keycloak zipkin prometheus grafana)
CONFIG_APPS=(application api-gateway product-service order-service payment-service inventory-service notification-service eureka-server)
EUREKA_CLIENTS=(api-gateway product-service order-service payment-service inventory-service notification-service)

failures=0
pass() { printf '  \033[32m✓\033[0m %s\n' "$1"; }
fail() { printf '  \033[31m✗\033[0m %s\n' "$1"; failures=$((failures + 1)); }
section() { printf '\n%s\n' "$1"; }

# Retries a command until it succeeds or WAIT_SECONDS have passed since the script started.
until_ok() {
  while ! "$@"; do
    (( SECONDS >= WAIT_SECONDS )) && return 1
    sleep 3
  done
}

compose() { docker compose -f "$COMPOSE_FILE" "$@"; }

container_status() {
  local id
  id="$(compose ps -q "$1" 2>/dev/null)"
  [[ -z "$id" ]] && { echo "missing"; return; }
  docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$id"
}

is_ready() {
  local status
  status="$(container_status "$1")"
  [[ "$status" == "healthy" || ( "$status" == "running" && "$1" == "zipkin" ) ]]
}

http_up() { curl -fsS --max-time 5 "$1" 2>/dev/null | grep -q '^{"status":"UP"'; }

registered_up() {
  curl -fsS --max-time 5 -H 'Accept: application/json' "$EUREKA_URL/eureka/apps/${1^^}" 2>/dev/null \
    | grep -q '"status":"UP"'
}

for tool in curl; do
  command -v "$tool" >/dev/null || { echo "$tool is required"; exit 2; }
done

echo "=========================================================="
echo " L0 check: infrastructure, config-server, eureka-server"
echo "=========================================================="

# ------------------------------------------------------------------ 1. infrastructure containers
section "1. Infrastructure containers ($COMPOSE_FILE)"
if [[ "$SKIP_INFRA" == "1" ]]; then
  echo "  (skipped: SKIP_INFRA=1)"
elif ! command -v docker >/dev/null || ! docker info >/dev/null 2>&1; then
  fail "Docker is not running (start it, or set SKIP_INFRA=1)"
else
  for svc in "${INFRA[@]}"; do
    if until_ok is_ready "$svc"; then
      pass "$svc is $(container_status "$svc")"
    else
      fail "$svc is $(container_status "$svc") (see: docker compose -f $COMPOSE_FILE logs $svc)"
    fi
  done
fi

# ------------------------------------------------------------------ 2. config-server
section "2. config-server ($CONFIG_URL)"
if until_ok http_up "$CONFIG_URL/actuator/health"; then
  pass "config-server health is UP"
  for app in "${CONFIG_APPS[@]}"; do
    body="$(curl -fsS --max-time 5 "$CONFIG_URL/$app/default" 2>/dev/null)"
    if grep -q "${app}.yml" <<<"$body"; then
      pass "serves $app.yml"
    else
      fail "does not serve $app.yml (GET $CONFIG_URL/$app/default)"
    fi
  done
else
  fail "config-server is not UP at $CONFIG_URL/actuator/health"
fi

# ------------------------------------------------------------------ 3. eureka-server
section "3. eureka-server ($EUREKA_URL)"
if until_ok http_up "$EUREKA_URL/actuator/health"; then
  pass "eureka-server health is UP"

  if [[ -n "${EXPECTED_APPS:-}" ]]; then
    read -r -a expected <<<"$EXPECTED_APPS"
  else
    expected=()
    if [[ "$SKIP_INFRA" != "1" ]] && command -v docker >/dev/null && docker info >/dev/null 2>&1; then
      for svc in "${EUREKA_CLIENTS[@]}"; do
        [[ "$(container_status "$svc")" != "missing" ]] && expected+=("$svc")
      done
    fi
  fi

  if (( ${#expected[@]} == 0 )); then
    echo "  (no platform service is running yet: nothing must be registered)"
  fi
  for app in "${expected[@]}"; do
    # A client registers ~30 s after it starts, so give it the remaining wait time.
    if until_ok registered_up "$app"; then
      pass "$app is registered and UP"
    else
      fail "$app is not registered as UP in Eureka"
    fi
  done
else
  fail "eureka-server is not UP at $EUREKA_URL/actuator/health"
fi

echo
echo "=========================================================="
if (( failures == 0 )); then
  echo " L0 GREEN: every check passed"
else
  echo " L0 RED: $failures check(s) failed"
fi
echo "=========================================================="
(( failures == 0 ))
