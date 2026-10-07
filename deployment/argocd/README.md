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
In PowerShell: `[Text.Encoding]::UTF8.GetString([Convert]::FromBase64String((kubectl -n argocd get secret argocd-initial-admin-secret -o jsonpath="{.data.password}")))`.

If antivirus software blocks the self-signed certificate, serve the UI over plain HTTP on the local cluster only:

```bash
kubectl -n argocd patch configmap argocd-cmd-params-cm --type merge -p '{"data":{"server.insecure":"true"}}'
kubectl -n argocd rollout restart deploy/argocd-server
kubectl -n argocd port-forward svc/argocd-server 8090:80     # http://localhost:8090
```

Services crash-loop until `infra` is healthy, then recover on their own (startup probe + restart).

## Private repo, no token: local git server (kind)

The repository and the GHCR packages are private. To run the same GitOps flow on kind without putting a GitHub
token in the cluster, build the images locally, load them into kind under the names `env/dev` points to, and let
ArgoCD read `env/dev` from a read-only git server on kind's Docker network. Used for the run on `main` (2026-10-07).

```bash
# images: same names and tags as env/dev (pullPolicy IfNotPresent uses the loaded copy)
for m in config-server eureka-server api-gateway product-service order-service payment-service \
         inventory-service notification-service review-service; do
  case $m in config-server|eureka-server|api-gateway) p=platform/$m ;; *) p=services/$m ;; esac
  docker build -f $p/Dockerfile -t ghcr.io/13nour11/capstone-platform/$m:<tag from env/dev> .
done
kind load docker-image --name capstone ghcr.io/13nour11/capstone-platform/<service>:<tag> ...

# git server with a bare copy of env/dev
git clone --bare . ~/.capstone-gitserver/capstone.git
git -C ~/.capstone-gitserver/capstone.git fetch "$PWD" +refs/remotes/origin/env/dev:refs/heads/env/dev
docker build -t capstone-gitserver:local deployment/argocd/local-git-server
docker run -d --name capstone-git --network kind -v ~/.capstone-gitserver:/repos:ro capstone-gitserver:local

# the same Applications, pointed at the local server (nothing changes in Git)
for f in deployment/argocd/infra-app.yaml deployment/argocd/services-appset.yaml; do
  sed 's|https://github.com/13nour11/capstone-platform.git|http://capstone-git:8000/cgi-bin/git/capstone.git|' $f \
    | kubectl apply -f -
done
```

If the node is slow to pull the infrastructure images, import the host's copies into kind's containerd:
`docker save --platform linux/amd64 postgres:16-alpine | docker exec -i capstone-control-plane ctr -n k8s.io images import --local -`.
To ship a new `env/dev` commit, fetch it into the bare repository again; ArgoCD picks it up on its next poll.

## Demo: drift is healed

```bash
kubectl -n ecommerce scale deploy/product-service --replicas=0
kubectl -n argocd get application product-service -w   # OutOfSync → Synced; the replica comes back
```
