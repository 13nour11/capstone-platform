#!/usr/bin/env bash
# L0 Definition of Done: infra healthy, config-server serving config, config-server registered in Eureka.
# Usage (repo root, after `docker compose ... up -d`): ./scripts/verify-l0.sh
set -uo pipefail

COMPOSE=(docker compose --env-file .env -f deployment/docker/docker-compose.yml)
fail=0
pass() { printf '  \033[32mPASS\033[0m %s\n' "$1"; }
bad()  { printf '  \033[31mFAIL\033[0m %s\n' "$1"; fail=1; }

echo "1. Containers healthy"
for svc in postgres zookeeper kafka redis keycloak zipkin prometheus grafana eureka-server config-server; do
  id=$("${COMPOSE[@]}" ps -q "$svc" 2>/dev/null)
  state=$([ -n "$id" ] && docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$id")
  if [ "$state" = healthy ]; then pass "$svc"; else bad "$svc (${state:-not running})"; fi
done

echo "2. Endpoints"
check() { # name url expected-substring
  if curl -fsS --max-time 5 "$2" 2>/dev/null | grep -q "$3"; then pass "$1"; else bad "$1 ($2)"; fi
}
check "config-server health"            http://localhost:8888/actuator/health                 '"UP"'
check "config-server serves config"     http://localhost:8888/product-service/default         'product_db'
check "eureka-server health"            http://localhost:8761/actuator/health                 '"UP"'
check "config-server registered"        http://localhost:8761/eureka/apps                     'CONFIG-SERVER'
check "keycloak realm"                  http://localhost:8180/realms/ecommerce-platform        'ecommerce-platform'
check "zipkin"                          http://localhost:9411/health                          'UP'
check "prometheus"                      http://localhost:9090/-/healthy                       'Healthy'

echo
if [ "$fail" -eq 0 ]; then echo "L0 OK"; else echo "L0 NOT READY"; exit 1; fi
