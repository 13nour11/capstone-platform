# Helm

One chart, `microservice/`, deploys every Spring Boot service; `values/<service>.yaml` holds what differs
(image, port, environment, Secret name). ArgoCD installs it per service (`deployment/argocd/`).

What every release gets:

| Concern | How |
|---|---|
| Probes | `startupProbe` (up to 3 min), `livenessProbe` `/actuator/health/liveness`, `readinessProbe` `/actuator/health/readiness` |
| Non-root | `runAsNonRoot`, uid/gid 10001 (same user as the Dockerfile), `seccompProfile: RuntimeDefault` |
| Hardening | `readOnlyRootFilesystem` + `/tmp` emptyDir, `allowPrivilegeEscalation: false`, drop `ALL` capabilities |
| Least privilege | dedicated ServiceAccount per service, no RBAC bindings, `automountServiceAccountToken: false` |
| Secrets | `envFrom` an existing Secret `<service>-secrets` (created by `scripts/create-k8s-secrets.sh`, never in Git) |
| Resources | requests 100m / 256Mi, limit 512Mi; the JVM uses 75 % of it (`MaxRAMPercentage` in the image) |
| Metrics | `prometheus.io/*` pod annotations for `/actuator/prometheus` |

Infrastructure is expected in namespace `infra` (`postgres.infra`, `kafka.infra:9092`, `redis.infra`, `keycloak.infra:8180`).
`KEYCLOAK_ISSUER_URI` must equal Keycloak's `KC_HOSTNAME` in the cluster, otherwise every call is 401 (`iss` mismatch).

## Standalone install (without ArgoCD)

```bash
helm lint deployment/helm/microservice -f deployment/helm/values/product-service.yaml
helm upgrade --install product-service deployment/helm/microservice \
  -f deployment/helm/values/product-service.yaml -n ecommerce --create-namespace
```

Local image instead of GHCR (kind):

```bash
docker build -f services/product-service/Dockerfile -t product-service:local .
kind load docker-image product-service:local --name capstone
helm upgrade --install product-service deployment/helm/microservice -f deployment/helm/values/product-service.yaml \
  -n ecommerce --set image.repository=product-service --set image.tag=local
```
