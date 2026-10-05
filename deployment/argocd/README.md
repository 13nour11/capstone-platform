# GitOps with ArgoCD

| File | What it deploys |
|---|---|
| `infra-app.yaml` | Application `infra`: raw manifests in `deployment/kubernetes/infra` → namespace `ecommerce` |
| `services-appset.yaml` | ApplicationSet `services`: one Application per service, chart `deployment/helm/microservice` + `deployment/helm/values/<service>.yaml` → namespace `ecommerce` |

Both use `automated` sync with `prune` and `selfHeal`, and track the **`env/dev`** branch. CI pushes
`ghcr.io/13nour11/capstone-platform/<service>:<sha>` (and `:latest`), merges `main` into `env/dev` and commits the
tag bump there (job `deploy-tags`), so `main` stays protected and `env/dev` history is never rewritten.

## Install (once per cluster)

```bash
kubectl create namespace argocd
kubectl apply -n argocd --server-side -f https://raw.githubusercontent.com/argoproj/argo-cd/stable/manifests/install.yaml
kubectl -n argocd rollout status deploy/argocd-server
git push origin main:env/dev        # first time only: create the tracked branch
scripts/create-k8s-secrets.sh       # Secrets never come from Git (NFR-04)
kubectl apply -f deployment/argocd/infra-app.yaml -f deployment/argocd/services-appset.yaml
```

UI: `kubectl -n argocd port-forward svc/argocd-server 8443:443` → https://localhost:8443, user `admin`,
password from `kubectl -n argocd get secret argocd-initial-admin-secret -o jsonpath="{.data.password}" | base64 -d`.

Services crash-loop until `infra` is healthy, then recover on their own (startup probe + restart).

## Demo: drift is healed

```bash
kubectl -n ecommerce scale deploy/product-service --replicas=0
kubectl -n argocd get application product-service -w   # OutOfSync → Synced; the replica comes back
```
