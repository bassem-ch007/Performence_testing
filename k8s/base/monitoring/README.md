# Azure single-node k3s monitoring

This folder replaces the earlier minimal monitoring scaffold. Deployment is managed by `kubectl apply -k k8s/overlays/azure`, not Docker Compose.

## Components and isolation
- Prometheus v3.5.0: gathers Kubernetes-staging annotated Spring `/actuator/prometheus` endpoints and kubelet cAdvisor pod/container CPU+memory metrics through Kubernetes API. Kubernetes RBAC is read-only. Retention 3 days.
- Grafana 12.1.0: provisioned Prometheus, Loki, and InfluxDB (InfluxQL) data sources and a starter live overview dashboard.
- Loki 3.5.3 + Fluent Bit 4.0.5 DaemonSet: central pod log aggregation using host `/var/log/containers` and Kubernetes labels; keep internal.
- InfluxDB 1.8: `jmeter` database for **JMeter's built-in InfluxDB Backend Listener** (InfluxDB v1 / InfluxQL protocol); internal Service port 8086. This is deliberately version 1.x to match JMeter's standard InfluxDB Backend Listener.
- Persistent PVCs in `monitoring`: Prometheus 3Gi, Grafana 1Gi, Loki 2Gi, InfluxDB 2Gi. On a single local-path node with ReclaimPolicy Delete. The database in `data` is unchanged.

Resource limits per tool are conservative initial ceilings; validate during actual high load. This monitoring is not HA. No public NodePorts, LoadBalancers, or Ingresses are created.

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
kubectl -n monitoring rollout status deployment/influxdb --timeout=240s
kubectl -n monitoring rollout status daemonset/fluent-bit --timeout=240s
kubectl -n monitoring get pods,svc,pvc
```
Create the Secret only once; if it exists, don't run `create` again. The Secret's password is not stored in Git. Use separate shell sessions for local forward commands.

## Private web access
On Azure VM: `kubectl -n monitoring port-forward svc/grafana 3000:3000 --address 127.0.0.1`
On your WSL laptop: `ssh -N -L 3000:127.0.0.1:3000 azureserver`
Then visit http://127.0.0.1:3000 on your laptop. No Azure NSG public rule required.

## Connect JMeter external to k3s InfluxDB
The collaborator's six JMX files **do not currently contain the InfluxDB Backend Listener**. Adding only `-JinfluxdbUrl` will not send results. Add the built-in Backend Listener in JMeter GUI (or update the source JMX with the collaborator) and configure:
- Implementation: `org.apache.jmeter.visualizers.backend.influxdb.InfluxdbBackendListenerClient`
- `influxdbUrl`: `http://127.0.0.1:8086/write?db=jmeter` when the SSH forwarding below is running on the **same JMeter laptop**.
- `application`: e.g. `workshop-monolith` or `workshop-microservices`; `measurement` typically `jmeter`.
- Retain JTL local results for complete post-run reports; Backend Listener is for live visualization, not replacement of raw samples.
- The JMX files may need the Backend Listener on the relevant TestPlan level and `run_id` tagging for per-run selection. Coordinate with the JMeter owner before editing.

For external JMeter on a laptop, run an Azure VM shell:
```bash
kubectl -n monitoring port-forward svc/influxdb 8086:8086 --address 127.0.0.1
```
And from that **same** JMeter laptop (or WSL with appropriate host port forwarding):
```bash
ssh -N -L 8086:127.0.0.1:8086 azureserver
```
Use the localhost InfluxDB URL only from where the forwarding actually terminates. Do NOT open unauthenticated InfluxDB 1.x, Loki, Prometheus, or Grafana to the public internet.

## Quick verification
```bash
kubectl -n monitoring get pods,svc,pvc
kubectl -n monitoring logs deployment/prometheus --tail=30
kubectl -n monitoring logs deployment/loki --tail=30
kubectl -n monitoring logs daemonset/fluent-bit --tail=30
kubectl -n monitoring exec deployment/influxdb -- influx -execute 'SHOW DATABASES'
kubectl -n monitoring port-forward svc/prometheus 9090:9090
```
Prometheus UI on `127.0.0.1:9090` can query `up`. Spring and cAdvisor metrics appear only once the appropriate targets are deployed and discovered. The starter dashboard is provisioned; existing Docker-specific dashboards in `monitoring/grafana/dashboards` were deliberately not copied because their labels/data sources require Kubernetes adaptation. We will calibrate JMeter influx metric panels after the Backend Listener is tested.

## Limitations
- JMeter test loads differ between the collaborator's monolith (80/15/5) and product-heavy microservices (65/15/20) plans; don't treat these as equivalent architecture baseline workloads.
- InfluxDB 1.8 is an isolated compatibility lab component without built-in authentication enabled here. **Only access through secured tunneling/VPN.**
- Test logs and dashboard data can contain identifiers. Keep them private and use controlled test records.
