#!/usr/bin/env bash
# Creates (or updates) the platform's Kubernetes Secrets from the repo-root .env, which is never
# committed (NFR-04). Safe to re-run: every object is applied, not created.
#
#   scripts/create-k8s-secrets.sh            # uses ./.env and namespace ecommerce
#   ENV_FILE=/path/.env NAMESPACE=demo scripts/create-k8s-secrets.sh
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE="${ENV_FILE:-$ROOT/.env}"
NAMESPACE="${NAMESPACE:-ecommerce}"
REALM_FILE="$ROOT/deployment/docker/keycloak/realm-export.json"
DASHBOARDS_DIR="$ROOT/deployment/docker/grafana/dashboards"
POSTGRES_INIT="$ROOT/deployment/docker/postgres/init-databases.sh"

fail() {
  echo "ERROR: $*" >&2
  exit 1
}

[[ -f "$ENV_FILE" ]] || fail "$ENV_FILE not found. Copy .env.example to .env and fill it in."
command -v kubectl >/dev/null || fail "kubectl is not on PATH."

# Load KEY=VALUE lines; tolerate Windows line endings in .env.
set -a
# shellcheck disable=SC1090
source <(sed -e 's/\r$//' "$ENV_FILE")
set +a

for name in POSTGRES_PASSWORD PRODUCT_DB_PASSWORD INVENTORY_DB_PASSWORD ORDER_DB_PASSWORD PAYMENT_DB_PASSWORD \
            REVIEW_DB_PASSWORD KEYCLOAK_ADMIN_PASSWORD KC_TEST_USER_PASSWORD ORDER_SERVICE_CLIENT_SECRET \
            GRAFANA_ADMIN_PASSWORD; do
  value="${!name:-}"
  [[ -n "$value" && "$value" != "change-me" ]] || fail "Set $name in $ENV_FILE."
done

apply() {
  kubectl --namespace "$NAMESPACE" create "$@" --dry-run=client --output yaml | kubectl apply --filename -
}

kubectl get namespace "$NAMESPACE" >/dev/null 2>&1 || kubectl create namespace "$NAMESPACE"

# PostgreSQL: superuser plus one password per service login; init-databases.sh creates the databases and logins.
apply secret generic postgres-credentials \
  --from-literal=POSTGRES_USER=postgres \
  --from-literal=POSTGRES_PASSWORD="$POSTGRES_PASSWORD" \
  --from-literal=PRODUCT_DB_PASSWORD="$PRODUCT_DB_PASSWORD" \
  --from-literal=INVENTORY_DB_PASSWORD="$INVENTORY_DB_PASSWORD" \
  --from-literal=ORDER_DB_PASSWORD="$ORDER_DB_PASSWORD" \
  --from-literal=PAYMENT_DB_PASSWORD="$PAYMENT_DB_PASSWORD" \
  --from-literal=REVIEW_DB_PASSWORD="$REVIEW_DB_PASSWORD"
apply configmap postgres-init --from-file=init-databases.sh="$POSTGRES_INIT"

# One Secret per service (Helm: secretName <service>-secrets, envFrom). Each holds only what that service needs.
apply secret generic product-service-secrets --from-literal=SPRING_DATASOURCE_PASSWORD="$PRODUCT_DB_PASSWORD"
apply secret generic inventory-service-secrets --from-literal=SPRING_DATASOURCE_PASSWORD="$INVENTORY_DB_PASSWORD"
apply secret generic payment-service-secrets --from-literal=SPRING_DATASOURCE_PASSWORD="$PAYMENT_DB_PASSWORD"
apply secret generic review-service-secrets --from-literal=SPRING_DATASOURCE_PASSWORD="$REVIEW_DB_PASSWORD"
apply secret generic order-service-secrets \
  --from-literal=SPRING_DATASOURCE_PASSWORD="$ORDER_DB_PASSWORD" \
  --from-literal=ORDER_SERVICE_CLIENT_SECRET="$ORDER_SERVICE_CLIENT_SECRET"

# Keycloak admin and the placeholders resolved inside realm-export.json at import time.
apply secret generic keycloak-admin \
  --from-literal=KC_BOOTSTRAP_ADMIN_PASSWORD="$KEYCLOAK_ADMIN_PASSWORD" \
  --from-literal=ORDER_SERVICE_CLIENT_SECRET="$ORDER_SERVICE_CLIENT_SECRET" \
  --from-literal=KC_TEST_USER_PASSWORD="$KC_TEST_USER_PASSWORD"

apply secret generic grafana-admin \
  --from-literal=GF_SECURITY_ADMIN_USER="${GRAFANA_ADMIN_USER:-admin}" \
  --from-literal=GF_SECURITY_ADMIN_PASSWORD="$GRAFANA_ADMIN_PASSWORD"

# The realm export carries only ${...} placeholders, but it travels as a Secret like the values it resolves.
[[ -s "$REALM_FILE" ]] || fail "$REALM_FILE is empty; the Keycloak realm must be exported first."
apply secret generic keycloak-realm --from-file=realm-export.json="$REALM_FILE"

# Dashboards are not secret, but keeping them here gives compose and Kubernetes one JSON source.
apply configmap grafana-dashboards --from-file="$DASHBOARDS_DIR"

if [[ -n "${GHCR_USERNAME:-}" && -n "${GHCR_TOKEN:-}" ]]; then
  apply secret docker-registry ghcr-pull \
    --docker-server=ghcr.io \
    --docker-username="$GHCR_USERNAME" \
    --docker-password="$GHCR_TOKEN"
else
  echo "GHCR_USERNAME/GHCR_TOKEN not set: skipping ghcr-pull (fine for public images or 'kind load')."
fi

echo "Secrets ready in namespace '$NAMESPACE'."
