# Performance workshop runbook — Azure k3s, 27 Nov 2026

## Access / architecture
Azure VM public IP: `20.240.55.238` (confirm it has not changed).
- Monolith HTTP NodePort `30080` — only when `ecommerce-mono` deployment scaled to 1
- Microservices HTTP NodePorts: user `30081`, product `30082`, order `30083`
- Grafana `30300` (requires login), Prometheus `30090` (unauthenticated; temporary access)
- Loki and PostgreSQL stay ClusterIP only. Grafana uses Loki and Prometheus internally.
- Do not apply `k8s/overlays/azure` during a running experiment: its staging Deployments all specify `replicas: 0`.

## Instructor — bootstrap and verify network
```bash
git pull origin workshop/k3s-azure-staging
kubectl apply -f k8s/workshop-access/nodeports.yaml
kubectl apply -f k8s/workshop-access/monitoring-nodeports.yaml
kubectl apply -k k8s/base/monitoring
kubectl -n monitoring rollout status deployment/grafana
kubectl -n staging get deploy,svc,pods
kubectl -n monitoring get pods,svc
```
Azure NSG must allow TCP `30080-30083` and `30090,30300` for the workshop. Protect SSH, PostgreSQL, Kubernetes and gRPC; close public rules after the lab. External checks from participant laptop:
```bash
curl -i --max-time 8 http://20.240.55.238:30300/api/health
curl -i --max-time 8 http://20.240.55.238:30090/-/ready
curl -i --max-time 8 http://20.240.55.238:30082/actuator/health
```

## 1 — Monolith load / spike, Grafana dashboard 01
```bash
kubectl -n staging scale deployment/user-service deployment/product-service deployment/order-service --replicas=0
kubectl -n staging scale deployment/ecommerce-mono --replicas=1
kubectl -n staging rollout status deployment/ecommerce-mono --timeout=240s
```
Check `http://20.240.55.238:30080/actuator/health`, then load-test using `loadtest/jmeter/monolithic/load-test.jmx` with `-q loadtest/jmeter/azure-monolith.properties`. Use a small smoke run first. JMeter main result: client-side throughput, percentiles, errors. Grafana 01: server-side throughput, counts by method/endpoint, p95/p99, 5xx, CPU. The collaborator's monolith spike plan is `loadtest/jmeter/monolithic/spike-test.jmx`. Defaults reach ~1500 users and require intentional capacity selection.

## 2 — Microservices load / spike, Grafana dashboard 02
```bash
kubectl -n staging scale deployment/ecommerce-mono --replicas=0
kubectl -n staging scale deployment/user-service deployment/product-service deployment/order-service --replicas=1
kubectl -n staging rollout status deployment/user-service --timeout=240s
kubectl -n staging rollout status deployment/product-service --timeout=240s
kubectl -n staging rollout status deployment/order-service --timeout=240s
```
Check NodePorts 30081, 30082, 30083. Use `loadtest/jmeter/microservices/load-test.jmx` with `-q loadtest/jmeter/azure-microservices.properties`. Dashboard 02 includes HTTP request types by service, HTTP p95/p99 and errors, CPU by pod and **gRPC client p95 from order-service and gRPC server p95 from user/product**. Compare RPC and HTTP series under identical traffic; they support diagnosis, not automatic proof of causality. Direct product-service HTTP delay is not the same path as order-service waiting for gRPC.

**Comparability caveat:** collaborator monolith and microservice JMX plans use different default request mixes (80/15/5 vs 65/15/20 and extra product views). Do not call their raw throughput an equal-load architecture comparison. Use the shared `ecommerce-load-test.jmx` with consistent workload and correct ports if direct architectural A/B comparison is required.

## 3 — Manual scaling, Grafana dashboard 03
```bash
kubectl -n staging scale deployment/product-service --replicas=3
kubectl -n staging rollout status deployment/product-service --timeout=240s
kubectl -n staging get pods -l app=product-service -o wide
```
Keep the original order-service image and one fixed JMeter load profile. Compare completed per-pod `grpc_server_seconds_count` and CPU. A dominant pod is a **hypothesis**, not a pre-established outcome. Direct product HTTP traffic and internal gRPC may distribute differently.

## 4 — gRPC client correction, Grafana dashboard 04
The corrected source is in draft PR #1, branch `workshop/grpc-round-robin-v2`. It must first be reviewed and a new **order-service** image published, e.g. `bassem00/perf-order-service:1.1.0`. The corrected order-service must be configured with `PRODUCT_GRPC_TARGET=dns:///product-service-grpc-headless:9092` and a supported explicit round-robin policy. Baseline image `1.0.0` uses `product-service:9092`.
Only after building/publishing/verifying the corrected image:
```bash
kubectl -n staging set env deployment/order-service PRODUCT_GRPC_TARGET='dns:///product-service-grpc-headless:9092'
bash scripts/workshop-deploy.sh order-service 1.1.0
kubectl -n staging rollout status deployment/order-service
```
Run **the same** microservice JMeter load profile as before with three product replicas and compare per-pod gRPC share, response latency and errors over equivalent windows. Caution: changing `set env` triggers an order-service Deployment rollout separately; execute before the test window. Revert baseline by restoring original env and old image, not image alone.

## 5 — Product rolling update, Grafana dashboard 05
Publish another versioned product-service image (e.g. `1.1.0`) from a validated source commit using the CI workflow. Existing product Deployment has `maxSurge:1`, `maxUnavailable:0` and readiness checks. While JMeter generates load, deploy:
```bash
bash scripts/workshop-deploy.sh product-service 1.1.0
kubectl -n staging get pods -l app=product-service -o wide
kubectl -n staging get pods -l app=product-service -o jsonpath='{range .items[*]}{.metadata.name}{"\t"}{.status.containerStatuses[0].imageID}{"\n"}{end}'
```
Grafana graphs show healthy scrape targets, HTTP traffic and 5xx responses; JMeter reports any failed requests/timeouts. A green rollout is not proof of zero errors—review both.

## CI (already built), and optional GitHub-driven CD
CI file: `.github/workflows/publish-workshop-image.yaml`; manually dispatch service, version tag and source ref to build/push Docker Hub. CI build/push is a separate step from CD. Do not overwrite a published semver tag with different content.

CD file: `.github/workflows/deploy-workshop-staging.yaml`; on manual dispatch, SSH to existing Azure server and run `scripts/workshop-deploy.sh` to set the single Deployment image, check rollout and revert image on failure. This workflow needs GitHub environment `workshop-staging` configured:
- Environment variables `WORKSHOP_SSH_HOST` = VM IP and `WORKSHOP_SSH_USER` = SSH username.
- Environment secrets `WORKSHOP_SSH_PRIVATE_KEY` = deploy key authorized on VM and `WORKSHOP_SSH_KNOWN_HOSTS` = the server's **verified** SSH host public key entry (from a trusted session; do not simply trust an unverified network scan).
- GitHub-hosted Actions runners must actually be able to reach VM TCP/22 through Azure NSG; unrestricted public SSH is **not** recommended. If access cannot be safely provided, run the same guarded script **on Azure via your existing SSH** and demonstrate GitHub Actions CI separately. The GitHub manual workflow must be present on the repo default branch to appear in the Actions tab; this branch is not yet merged. Do not expose private keys in Git or logs.
- Use repository environment approval if appropriate. The deployment script refuses an inactive Deployment (0 replicas) and does not run `kubectl apply -k k8s/overlays/azure`.
- Source PR #1 (gRPC) is separate from staging PR #2, so do not assume the fixed image already exists merely because CD is committed.

## Wrap-up / safety
Keep the six Grafana dashboards in the **Performance workshop** folder: 00 overview, 01 monolith, 02 microservices, 03 scale, 04 gRPC correction, 05 rolling update. JMeter JTL reports provide load-side evidence. Grafana/Prometheus series describe the server side.
Do not share admin credentials with participants; give them a Grafana Viewer login. Clean up synthetic data if required, and close Azure NSG public monitoring/application access after the supervised session.
