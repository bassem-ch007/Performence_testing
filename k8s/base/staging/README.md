# Staging workloads

All four Deployments are committed with **replicas: 0**. This ensures applying the Azure overlay does not start the two architectures together. No production environment.

| Deployment | HTTP | gRPC | CPU limit | RAM limit |
|---|---:|---:|---:|---:|
| ecommerce-mono | 8080 | — | 1500m | 3Gi |
| user-service | 8081 | 9091 | 500m | 1Gi |
| product-service | 8082 | 9092 | 500m | 1Gi |
| order-service | 8083 | 9093 | 500m | 1Gi |

Apps use the `staging` Spring profile with DB URL `jdbc:postgresql://postgres.data.svc.cluster.local:5432/ecommercedb`, `SPRING_JPA_SHOW_SQL=false`, and JSON console logs. They read `POSTGRES_USER` and `POSTGRES_PASSWORD` from **Secret `app-postgres-auth` inside `staging`**; Kubernetes Secrets are namespace-scoped and cannot reference `data/postgres-auth` directly.

## Published baseline images
The four Deployment manifests reference the Docker Hub baseline images `bassem00/perf-monolith:1.0.0`, `bassem00/perf-user-service:1.0.0`, `bassem00/perf-product-service:1.0.0`, and `bassem00/perf-order-service:1.0.0`. These were built and deployed during Azure lab setup. Future images such as `1.1.0` must be published separately before using CD. Version tags are not sufficient proof of source contents: verify image digests and rollout readiness.

## Create staging DB secret safely on Azure
Use a new password prompt for the same PostgreSQL password. This avoids decrypting/copying the existing cluster Secret:

```bash
read -rsp "Existing PostgreSQL password: " APP_DB_PASSWORD; echo
kubectl -n staging create secret generic app-postgres-auth \
  --from-literal=POSTGRES_USER=workshop \
  --from-literal=POSTGRES_PASSWORD="$APP_DB_PASSWORD"
unset APP_DB_PASSWORD
```

Never commit this Secret. If it already exists, do not recreate it blindly. In production each service should have its own DB account with least privileges, but this controlled lab reuses the original credential.

## Safe rollout plan

```bash
kubectl kustomize k8s/overlays/azure >/tmp/workshop-rendered.yaml
kubectl apply --dry-run=server -k k8s/overlays/azure
kubectl apply -k k8s/overlays/azure
kubectl get deployments,services -n staging
```

After publishing images and setting digests, start only monolith:

```bash
kubectl -n staging scale deployment/ecommerce-mono --replicas=1
kubectl -n staging rollout status deployment/ecommerce-mono --timeout=240s
```

For the following microservice experiment first scale monolith to zero, then start user/product/order with 1 replica each. The baseline order client uses the regular `product-service:9092` ClusterIP; the future corrected client will target `dns:///product-service-grpc-headless:9092` with explicit round_robin configuration. Both Services are present from day one.

Kustomize currently declares replicas=0. Reapplying the same overlay after manual scaling will set them back to zero: **do not blindly reapply the full overlay during active tests.** Later create architecture overlays or update replicas declaratively.

HTTP access for workshop participants uses separate Services in `k8s/workshop-access/nodeports.yaml`: monolith `30080`, user `30081`, product `30082`, order `30083`. Monitoring external access is in `k8s/workshop-access/monitoring-nodeports.yaml`: Grafana `30300` and Prometheus `30090`. The Azure NSG must allow these ports for attendees; remove public access after the session. Use `k8s/workshop-access/WORKSHOP-RUNBOOK.md` for architecture switching, JMeter smoke tests, gRPC correction and CD.
