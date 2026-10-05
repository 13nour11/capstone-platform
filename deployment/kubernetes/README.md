# Kubernetes (kind)

Local cluster plus the platform's infrastructure: PostgreSQL, Kafka + Zookeeper, Redis, Keycloak, Zipkin, Prometheus and Grafana.

- The 8 services are installed by the Helm chart in `deployment/helm` (owner A).
- ArgoCD in `deployment/argocd` syncs both (owner A).
- Everything runs in namespace **`ecommerce`**, under the same host names as docker compose (`postgres`, `kafka:29092`, `keycloak:8180`, …), so service configuration is identical in both environments.

## Prerequisites

| Tool | Check |
|---|---|
| Docker Desktop, 6 GB+ memory | `docker info` |
| kind | `kind version` |
| kubectl | `kubectl version --client` |
| `deployment/docker/.env` | copied from `.env.example`, no `change-me` left |

**Stop docker compose first:** kind binds the same host ports (8180, 9411, 9090, 3000).

## Bring the cluster up

```bash
kind create cluster --config deployment/kubernetes/kind-config.yaml
kubectl apply -f deployment/kubernetes/infra/namespace.yaml
scripts/create-k8s-secrets.sh
kubectl apply -f deployment/kubernetes/infra/
kubectl -n ecommerce wait --for=condition=Ready pod --all --timeout=600s
```

## What the secrets script creates

| Object | Keys | Used by |
|---|---|---|
| Secret `postgres-credentials` | `POSTGRES_USER`, `POSTGRES_PASSWORD` | PostgreSQL |
| Secret `db-credentials` | `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD` | product, order, inventory, payment (`envFrom` in Helm) |
| Secret `keycloak-admin` | `KEYCLOAK_ADMIN`, `KEYCLOAK_ADMIN_PASSWORD` | Keycloak |
| Secret `keycloak-realm` | `realm-export.json` | Keycloak realm import |
| Secret `grafana-admin` | `GF_SECURITY_ADMIN_USER`, `GF_SECURITY_ADMIN_PASSWORD` | Grafana |
| ConfigMap `grafana-dashboards` | the JSON files in `deployment/docker/grafana/dashboards` | Grafana |
| Secret `ghcr-pull` (only if `GHCR_USERNAME`/`GHCR_TOKEN` are set) | registry login | `imagePullSecrets` for private GHCR images |

## Contract with the Helm chart (owner A)

| Item | Value |
|---|---|
| Namespace | `ecommerce` |
| Service names | Release names equal the compose service names (`payment-service`, …), because Prometheus scrapes `<name>:<port>/actuator/prometheus` |
| Gateway Service | type NodePort, `nodePort: 30080`, mapped to host port 30080 by `kind-config.yaml` |
| Database credentials | `envFrom: secretRef: db-credentials` |
| Service env | `SPRING_KAFKA_BOOTSTRAP_SERVERS=kafka:29092`, `MANAGEMENT_ZIPKIN_TRACING_ENDPOINT=http://zipkin:9411/api/v2/spans`, `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI=http://localhost:8180/realms/ecommerce-platform`, `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_JWK_SET_URI=http://keycloak:8180/realms/ecommerce-platform/protocol/openid-connect/certs` |
| Images | `ghcr.io/13nour11/capstone-platform/<service>:<commit-sha>` from CI, or a local build loaded with `kind load docker-image <image> --name capstone` |

## Memory

The infrastructure requests about 1.6 GB, and each service needs about 384 MB. On a 6 GB Docker VM:
- run one replica per service;
- keep compose stopped while the cluster runs.

## Tear down

```bash
kind delete cluster --name capstone
```
