# Azure single-node k3s monitoring

This folder replaces the earlier minimal monitoring scaffold. Deployment is managed by `kubectl apply -k k8s/base/monitoring`, not Docker Compose.

## Components and isolation
- Prometheus v3.5.0: gathers Kubernetes-staging annotated Spring `/actuator/prometheus` endpoints and kubelet cAdvisor pod/container CPU+memory metrics through Kubernetes API. Kubernetes RBAC is read-only. Retention 3 days.
- Grafana 12.1.0: six stage-specific workshop dashboards (34 panels total) using Prometheus. The collaborator's original detailed dashboards remain available as reference in their repository.
- Loki 3.5.3 + Fluent Bit 4.0.5 DaemonSet: central pod log aggregation using host `/var/log/containers` and Kubernetes labels; keep internal.
- Persistent PVCs in `monitoring`: Prometheus 3Gi, Grafana 1Gi, Loki 2Gi. On a single local-path node with ReclaimPolicy Delete. The database in `data` is unchanged.

Resource limits per tool are conservative initial ceilings; validate during actual high load. This monitoring is not HA. Public NodePorts are managed separately in `k8s/workshop-access/monitoring-nodeports.yaml` (Grafana 30300 and Prometheus 30090). These components' base Services remain ClusterIP. Keep Loki and Fluent Bit internal.

## Refresh monitoring dashboards safely (on Azure VM)

The Kubernetes applications are already deployed. Update **only** the monitoring namespace to avoid resetting application replicas:

```bash
cd ~/Performence_testing
git pull --ff-only origin main
export KUBECONFIG="$HOME/.kube/config"
kubectl apply --dry-run=server -k k8s/base/monitoring
kubectl apply -k k8s/base/monitoring
kubectl -n monitoring rollout status deployment/grafana --timeout=240s
kubectl -n monitoring get pods
```

The `grafana-admin` Secret already exists on the workshop cluster; do **not** recreate it or reset passwords during the class. If provisioned panels do not reload automatically from ConfigMap, check provider file reload or restart only Grafana using `kubectl -n monitoring rollout restart deployment/grafana`. Never reapply the full Azure overlay during an active workshop, because its staging manifests specify zero replicas.

## Private web access
On Azure VM: `kubectl -n monitoring port-forward svc/grafana 3000:3000 --address 127.0.0.1`
On your WSL laptop: `ssh -N -L 3000:127.0.0.1:3000 azureserver`
Then visit http://127.0.0.1:3000 on your laptop. No Azure NSG public rule required.

## Stage 03 — baseline gRPC test: six useful panels

Dashboard Stage 03 uses **six panels only**: healthy observed product targets, recorded product gRPC calls, absolute per-pod gRPC call count, per-pod gRPC calls/sec, per-pod direct HTTP requests/sec, and gRPC request **percentage share by pod**. The last metric uses `scalar(sum(...))` for the denominator: dividing a pod-labeled vector by an unlabeled vector previously produced a misleading **No data** result because of Prometheus vector matching.

**Observed on 27 November 2026, during a 2,550-thread JMeter spike:** Grafana showed three healthy product targets and about **3.26k completed incoming gRPC calls**, all attributed to product pod suffix `8rxr7` over the shown dashboard time interval; direct product HTTP requests were distributed across three pods. This supports severe gRPC connection affinity/imbalance with baseline order-service, but does not identify the cause of every JMeter failure. JMeter at 3m36s: **73,503 samples, 50,801 failed (69.11%)**, up to 42.5 s per sample. The example is an overloaded spike, not a steady-state capacity measurement.

The previous Stage 03 HTTP per-pod error cards all showed zero even though JMeter had failures; these panels have been **removed**. The server-side product metric only sees its own HTTP responses, not JMeter transport failures, gRPC failures, or failures returned by order-service. Read **JMeter CSV JTL** (use `loadtest/jmeter/analyze-jtl.py`) for actual client-visible failure labels/codes, including nonnumeric synthetic failures. Never equate HTTP 4xx/5xx counters with gRPC failure rates.

For before/after comparison, run the **same controlled order-heavy traffic** with Stage 03 and Stage 04. Choose the same time window in Grafana and collect JTL stats; do not mix a 2,550-user overload spike with a low-user correction run and claim improvement solely due to the code change.

## Stage 04 — directly comparable gRPC correction dashboard

Stage 04 now mirrors the **exact same six panels, query expressions, units, positions and time settings** as Stage 03. The only dashboard differences are the title and experiment description. Compare Stage 03 `order-service:1.0.0` with Stage 04 corrected `order-service:1.1.0` using the **same JMeter scenario, concurrency, ramp, seed volumes and a matching analysis interval**. Check the order-service image and `PRODUCT_GRPC_TARGET=dns:///product-service-grpc-headless:9092`; the dashboard itself cannot establish which order-service version produced a historical timeseries. Do not view both dashboards at the same current time and interpret them as separate independent runs, since both query the same Prometheus data.

Expected *hypothesis*: per-pod gRPC distribution may become less concentrated with headless DNS and round-robin. Three replicas do not guarantee exactly one third each and the fix is not proven until measured. The REST panels can provide a control comparison. JMeter is the source for end-to-end errors under stress, even when server-side HTTP error counters read zero.

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
Prometheus UI on `127.0.0.1:9090` can query `up`. Spring and cAdvisor metrics appear only once the appropriate targets are deployed and discovered. Six beginner-focused dashboards are provisioned: `00 Cluster overview` (4 panels); `01 Monolith load & spike` (7 panels including throughput, HTTP request type/volume, p95/p99, errors and CPU); `02 Microservices load & spike` (10 panels: throughput, requests by service/type, HTTP p95/p99 and errors, CPU, gRPC throughput and both gRPC client/server p95 latency); `03 Manual scaling` (3 panels); `04 gRPC correction` (3 panels); `05 Rolling update` (3 panels). JMeter provides virtual users and client-side results; Grafana shows observed server-side metrics.

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

- The microservices 5xx rate panel uses a zero-valued fallback for applications with successful traffic but no 5xx series. If a service has no requests at all, it will not be shown as a 0% error rate.
- HTTP requests per endpoint are grouped using the Spring Boot `application`, `method`, and `uri` metric labels. They are server-side counts; the JMeter report measures client-side requests/errors, including connection failures that may not reach the server.

- **gRPC diagnosis:** Compare HTTP p95/p99 of order-service with the `grpc_client_seconds_bucket` p95 grouped by `rpc_method`, and the corresponding `grpc_server_seconds_bucket` p95 grouped by receiving `application` and `rpc_method`. Higher order-service latency may be partly due to waiting for remote RPCs, but time series alone do not prove causal attribution. Product-service's direct HTTP latency is a separate path; do not attribute it to gRPC without measurements of load contention. JMeter's HTTP percentiles reflect client-observed end-to-end latency.

## Mature error monitoring (server versus load-generator)

Workshop dashboards **00, 01, 02, and 05** now show HTTP status-code breakdowns and server-observed error percentages for `4xx`, `5xx` and nonstandard `6xx`. Existing 5xx-only graphs were expanded to `4xx/5xx/6xx`. Status-specific series remain absent if the server never emits that class.

**Prometheus cannot observe a response that never reaches the application.** Examples: `java.net.ConnectException`, refused connection, TLS failure, DNS failure, JMeter timeout, or an assertion on an otherwise successful HTTP 200. Report these from **client-side JMeter CSV JTL**. An HTTP `404` may indicate a broken test ID, not an infrastructure outage; distinguish expected business 4xx from errors for each scenario. HTTP 6xx responses are **not standard HTTP status codes**; JMeter may instead display a nonnumeric synthetic response code such as `Non HTTP response code: java.net.SocketTimeoutException`, which the JTL analyzer classifies under transport/non-HTTP.

On the **load-generator machine** with Python 3 and the saved JMeter report:
```bash
python3 loadtest/jmeter/analyze-jtl.py /tmp/workshop-cicd-05/results.jtl \
  --json /tmp/workshop-cicd-05/error-summary.json
```
The analyzer returns exit code **1 if any JMeter sample failed**, so it can be used as a CI gate. It reports total failed samples (`success=false`), exact response codes, failure labels, elapsed p50/p95/p99, and sample transport/HTTP/assertion errors. Existing JMX files are unmodified. During release comparisons, exclude setup traffic from the interpretation or measure separately; timestamps and labels help identify the phases.

**Monitoring-only refresh on Azure VM (safe for existing application replica counts):**
```bash
cd ~/Performence_testing
git pull --ff-only origin main
export KUBECONFIG="$HOME/.kube/config"
kubectl apply --dry-run=server -k k8s/base/monitoring
kubectl apply -k k8s/base/monitoring
kubectl -n monitoring rollout status deployment/grafana --timeout=240s
```
Avoid applying `k8s/overlays/azure`: application manifests specify 0 replicas. Grafana provisioned dashboard ConfigMap changes may require a Grafana restart or dashboard-provider reload if the mounted files do not refresh. Verify a newly added status panel appears before load testing. HTTP health-probe continuity plus a JTL with **zero business-request failures** under sustained traffic supports *zero observed downtime under the tested workload*, not a universal guarantee.
