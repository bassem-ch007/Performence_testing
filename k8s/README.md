# Azure k3s workshop manifests

This tree is an initial, deliberately narrow Kubernetes/Kustomize scaffold for the **Azure** workshop VM. It does **not** create a production namespace. Application and monitoring manifests will be added after verification.

## Topology

- `data`: PostgreSQL 17 and persistent 8Gi PVC on the single-node k3s local-path StorageClass
- `monitoring`: reserved namespace; future Grafana, Prometheus and JMeter/InfluxDB components
- `staging`: reserved namespace; monolith OR microservices, with controlled rollout experiments

The PostgreSQL container has a 500m CPU / 1536Mi memory limit. Monolith and microservices will each receive an equal **1.5 CPU / 3Gi** total baseline limit. JMeter must run off the server.

## Secret requirement

Before starting PostgreSQL, create `postgres-auth` in namespace `data` with keys `POSTGRES_USER` and `POSTGRES_PASSWORD`. Do not commit the actual password, a kubeconfig, or secret manifests containing real values. `POSTGRES_DB` defaults to `ecommercedb`.

Example interactive setup on the Azure VM:

```bash
read -rsp "PostgreSQL password: " PG_PASSWORD; echo
kubectl -n data create secret generic postgres-auth \
  --from-literal=POSTGRES_USER=workshop \
  --from-literal=POSTGRES_PASSWORD="$PG_PASSWORD"
unset PG_PASSWORD
```

If the Secret already exists, inspect it rather than overwriting it.

## Review and deployment

```bash
kubectl kustomize k8s/overlays/azure
kubectl apply --dry-run=server -k k8s/overlays/azure
kubectl apply -k k8s/overlays/azure
kubectl -n data rollout status statefulset/postgres --timeout=180s
kubectl get pods,svc,pvc -n data
```

The `local-path` StorageClass has reclaim policy `Delete`. Do not delete the PVC unless deliberately resetting database storage. Use backup/seed fixtures for reproducible comparisons. This folder is infrastructure-as-code, not evidence that it has been deployed.
