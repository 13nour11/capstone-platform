#!/usr/bin/env bash
# Creates (or updates) the platform's Kubernetes Secrets from deployment/docker/.env, which is
# never committed (NFR-04). Safe to re-run: every object is applied, not created.
#
#   scripts/create-k8s-secrets.sh            # uses deployment/docker/.env and namespace ecommerce
#   ENV_FILE=/path/.env NAMESPACE=demo scripts/create-k8s-secrets.sh
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE="${ENV_FILE:-$ROOT/deployment/docker/.env}"
NAMESPACE="${NAMESPACE:-ecommerce}"
REALM_FILE="$ROOT/deployment/docker/keycloak/realm-export.json"
DASHBOARDS_DIR="$ROOT/deployment/docker/grafana/dashboards"

fail() {
  echo "ERROR: $*" >&2
  exit 1
}

[[ -f "$ENV_FILE" ]] || fail "$ENV_FILE not found. Copy deployment/docker/.env.example to .env and fill it in."
command -v kubectl >/dev/null || fail "kubectl is not on PATH."

# Load KEY=VALUE lines; tolerate Windows line endings in .env.
set -a
# shellcheck disable=SC1090
source <(sed -e 's/\r$//' "$ENV_FILE")
set +a

for name in POSTGRES_PASSWORD KEYCLOAK_ADMIN_PASSWORD GRAFANA_ADMIN_PASSWORD ORDER_SERVICE_CLIENT_SECRET KC_TEST_USER_PASSWORD; do
  value="${!name:-}"
  [[ -n "$value" && "$value" != "change-me" ]] || fail "Set $name in $ENV_FILE."
done

apply() {
  kubectl --namespace "$NAMESPACE" create "$@" --dry-run=client --output yaml | kubectl apply --filename -
}

kubectl get namespace "$NAMESPACE" >/dev/null 2>&1 || kubectl create namespace "$NAMESPACE"

# PostgreSQL superuser (infra) and the same credentials under Spring's names (services, via envFrom).
apply secret generic postgres-credentials \
  --from-literal=POSTGRES_USER="${POSTGRES_USER:-postgres}" \
  --from-literal=POSTGRES_PASSWORD="$POSTGRES_PASSWORD"
apply secret generic db-credentials \
  --from-literal=SPRING_DATASOURCE_USERNAME="${POSTGRES_USER:-postgres}" \
  --from-literal=SPRING_DATASOURCE_PASSWORD="$POSTGRES_PASSWORD"

apply secret generic keycloak-admin \
  --from-literal=KEYCLOAK_ADMIN="${KEYCLOAK_ADMIN:-admin}" \
  --from-literal=KEYCLOAK_ADMIN_PASSWORD="$KEYCLOAK_ADMIN_PASSWORD"

# Resolves the ${...} placeholders in realm-export.json at import time.
apply secret generic keycloak-realm-env \
  --from-literal=ORDER_SERVICE_CLIENT_SECRET="$ORDER_SERVICE_CLIENT_SECRET" \
  --from-literal=KC_TEST_USER_PASSWORD="$KC_TEST_USER_PASSWORD"

apply secret generic grafana-admin \
  --from-literal=GF_SECURITY_ADMIN_USER="${GRAFANA_ADMIN_USER:-admin}" \
  --from-literal=GF_SECURITY_ADMIN_PASSWORD="$GRAFANA_ADMIN_PASSWORD"

# The realm export holds client secrets, so it travels as a Secret, not a ConfigMap.
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
