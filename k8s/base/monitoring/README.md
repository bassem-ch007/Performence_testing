# Azure single-node k3s monitoring

This folder replaces the earlier minimal monitoring scaffold. Deployment is managed by `kubectl apply -k k8s/overlays/azure`, not Docker Compose.

## Components and isolation
- Prometheus v3.5.0: gathers Kubernetes-staging annotated Spring `/actuator/prometheus` endpoints and kubelet cAdvisor pod/container CPU+memory metrics through Kubernetes API. Kubernetes RBAC is read-only. Retention 3 days.
- Grafana 12.1.0: six concise, stage-specific workshop dashboards (23 panels total) using Prometheus. The collaborator's original detailed dashboards remain available as reference in their repository.
- Loki 3.5.3 + Fluent Bit 4.0.5 DaemonSet: central pod log aggregation using host `/var/log/containers` and Kubernetes labels; keep internal.
- Persistent PVCs in `monitoring`: Prometheus 3Gi, Grafana 1Gi, Loki 2Gi. On a single local-path node with ReclaimPolicy Delete. The database in `data` is unchanged.

Resource limits per tool are conservative initial ceilings; validate during actual high load. This monitoring is not HA. Public NodePorts are managed separately in `k8s/workshop-access/monitoring-nodeports.yaml` (Grafana 30300 and Prometheus 30090). These components' base Services remain ClusterIP. Keep Loki and Fluent Bit internal.

## Deploy (on Azure VM)
```bash
git pull origin workshop/k3s-azure-staging
kubectl get secret grafana-admin -n monitoring || true
read -rsp "Grafana password: " GRAFANA_PASSWORD; echo
kubectl -n monitoring create secret generic grafana-admin --from-literal=password="$GRAFANA_PASSWORD"
unset GRAFANA_PASSWORD
kubectl kustomize k8s/overlays/azure >/tmp/workshop-rendered.yaml
kubectl apply --dry-run=server -k k8s/overlays/azure
kubectl apply -k k8s/overlays/azure
kubectl -n monitoring rollout status deployment/prometheus --timeout=240s
kubectl -n monitoring rollout status deployment/grafana --timeout=240s
kubectl -n monitoring rollout status deployment/loki --timeout=240s
kubectl -n monitoring rollout status daemonset/fluent-bit --timeout=240s
kubectl -n monitoring get pods,svc,pvc
```
Create the Secret only once; if it exists, don't run `create` again. The Secret's password is not stored in Git. Use separate shell sessions for local forward commands.

## Private web access
On Azure VM: `kubectl -n monitoring port-forward svc/grafana 3000:3000 --address 127.0.0.1`
On your WSL laptop: `ssh -N -L 3000:127.0.0.1:3000 azureserver`
Then visit http://127.0.0.1:3000 on your laptop. No Azure NSG public rule required.

## JMeter metrics
JMeter test results remain on the participant machines as JTL/HTML reports. The current JMX plans are not wired to InfluxDB, and InfluxDB is intentionally **not part of this workshop cluster**. Grafana displays Spring Boot, Kubernetes and pod metrics via Prometheus and log events via Loki. Do not add an InfluxDB Backend Listener to JMX files.

## Quick verification
```bash
kubectl -n monitoring get pods,svc,pvc
kubectl -n monitoring logs deployment/prometheus --tail=30
kubectl -n monitoring logs deployment/loki --tail=30
kubectl -n monitoring logs daemonset/fluent-bit --tail=30
kubectl -n monitoring port-forward svc/prometheus 9090:9090
```
Prometheus UI on `127.0.0.1:9090` can query `up`. Spring and cAdvisor metrics appear only once the appropriate targets are deployed and discovered. Six beginner-focused dashboards are provisioned: `00 Cluster overview` (4 panels); `01 Monolith load & spike` (4 panels including p95/p99); `02 Microservices load & spike` (6 panels including p95/p99); `03 Manual scaling` (3 panels); `04 gRPC correction` (3 panels); `05 Rolling update` (3 panels). JMeter provides virtual users and client-side results; Grafana shows observed server-side metrics.

## Limitations
- JMeter test loads differ between the collaborator's monolith (80/15/5) and product-heavy microservices (65/15/20) plans; don't treat these as equivalent architecture baseline workloads.
- Test logs and dashboard data can contain identifiers. Keep them private and use controlled test records.

## Dashboard validation / methodological caveats
- The collaborator's original Docker Compose dashboards (44 monolith panels, 52 microservices panels) remain preserved in their repository. The workshop dashboards intentionally show only the metrics required by each teaching stage. Do not mistake missing series for zero traffic.
- Direct HTTP load on `product-service` and order-service → product-service gRPC use different paths and load-balancing behavior. A **dominant pod is a hypothesis**, not a guaranteed outcome. Compare `grpc_server_seconds_count` by `pod` and its derived percentage under the same JMeter scenario before and after the corrected client; keep product replica count fixed at three for the correction phase.
- The pod-replica graphs use the number of healthy scrape targets, not the Kubernetes Deployment desired replica count. For rollout source-image verification use `kubectl -n staging get pods -o wide` and actual `imageID` via `kubectl`, and correlate to Grafana. No kube-state-metrics exporter is installed.
- Grafana is provisioned from `configMapGenerator`. To update dashboards without resetting staging `replicas: 0`: `kubectl apply -k k8s/base/monitoring` and `kubectl -n monitoring rollout status deployment/grafana`. Never reapply the Azure overlay during manual workshop scaling.
- p95/p99 are Prometheus histogram estimates of server-side HTTP latency, not the JMeter client-side percentiles; JMeter JTL/HTML results remain necessary.
- Public Grafana is login protected. Prometheus is not authenticated at the app layer; disable its public NSG access after the workshop.
