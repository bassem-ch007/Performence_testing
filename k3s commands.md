# k3s commands — Azure performance workshop

**Server:** `vm-performance-lab` · **Cluster:** single-node k3s · **Namespaces:** `staging`, `data`, `monitoring`.

These commands are meant to be **copied and pasted directly inside the Azure VM SSH terminal**. Most commands are **read-only**. Commands that change the cluster are grouped at the bottom and explicitly marked.

## 0. Connect and configure kubectl (once per SSH session)

From **WSL**, connect:

```bash
ssh azureserver
```

Now, **inside the Azure VM**, run:

```bash
export KUBECONFIG="$HOME/.kube/config"
kubectl get nodes -o wide
```

> The kubeconfig in `/etc/rancher/k3s/k3s.yaml` is root-owned. Use `~/.kube/config` with the explicit environment variable, as shown above.

## 1. Quick overview — what is active?

```bash
kubectl -n staging get deployments
```

```bash
kubectl -n staging get pods -o wide
```

```bash
kubectl get pods -A -o wide
```

```bash
kubectl -n staging get services
```

```bash
kubectl get nodes -o wide
```

Read the output: **READY** tells you how many containers are ready; **STATUS** should normally be `Running`; **RESTARTS** should not keep increasing.

## 2. Continuous monitoring — refresh automatically

Refresh active application pods every 2 seconds:

```bash
watch -n 2 'kubectl -n staging get pods -o wide'
```

Refresh deployment replicas every 2 seconds:

```bash
watch -n 2 'kubectl -n staging get deployments'
```

Refresh the full cluster's pods every 3 seconds:

```bash
watch -n 3 'kubectl get pods -A'
```

Press **Ctrl+C** to stop a watch.

## 3. CPU and RAM during JMeter tests

```bash
kubectl -n staging top pods
```

```bash
kubectl top nodes
```

```bash
watch -n 3 'kubectl -n staging top pods'
```

> If `kubectl top` returns `Metrics API not available`, the metrics-server is unavailable or has not collected metrics. Use Grafana/Prometheus for workshop performance graphs.

## 4. Check current images and replica counts

```bash
kubectl -n staging get deployments -o custom-columns='NAME:.metadata.name,DESIRED:.spec.replicas,READY:.status.readyReplicas,AVAILABLE:.status.availableReplicas,IMAGE:.spec.template.spec.containers[*].image'
```

```bash
kubectl -n staging get deployment product-service -o wide
```

```bash
kubectl -n staging get deployment order-service -o wide
```

## 5. Check Stage 03 / 04 gRPC behavior

Show the order-service target and load-balancing configuration (only these two variables):

```bash
kubectl -n staging get deployment order-service -o jsonpath='{range .spec.template.spec.containers[0].env[?(@.name=="PRODUCT_GRPC_TARGET")]}{.name}={.value}{"\n"}{end}{range .spec.template.spec.containers[0].env[?(@.name=="GRPC_LOAD_BALANCING_POLICY")]}{.name}={.value}{"\n"}{end}'
```

Check the headless gRPC Service and its actual endpoints:

```bash
kubectl -n staging get svc product-service-grpc-headless -o wide
```

```bash
kubectl -n staging get endpointslices -l kubernetes.io/service-name=product-service-grpc-headless -o wide
```

List the three product-service pods and their IPs:

```bash
kubectl -n staging get pods -l app=product-service -o wide
```

When Stage 03 is active, expect **3 product-service replicas** with the baseline order image; Stage 04 switches the order-service image and its gRPC DNS target.

## 6. PostgreSQL and monitoring services

```bash
kubectl -n data get statefulset,pods,services,pvc
```

```bash
kubectl -n monitoring get deployments,pods,services
```

```bash
kubectl -n monitoring get daemonsets
```

Useful public workshop URLs (open them from your laptop):
- Monolith: http://20.240.55.238:30080/actuator/health
- User: http://20.240.55.238:30081/actuator/health
- Product: http://20.240.55.238:30082/actuator/health
- Order: http://20.240.55.238:30083/actuator/health
- Grafana: http://20.240.55.238:30300
- Prometheus: http://20.240.55.238:30090

## 7. Logs and troubleshooting

Show recent logs from a Deployment:

```bash
kubectl -n staging logs deployment/ecommerce-mono --tail=80
```

```bash
kubectl -n staging logs deployment/product-service --tail=80
```

```bash
kubectl -n staging logs deployment/order-service --tail=80
```

Follow new logs in real time (**Ctrl+C** to stop):

```bash
kubectl -n staging logs -f deployment/order-service --tail=40
```

Inspect pod failures and Kubernetes events:

```bash
kubectl -n staging get events --sort-by=.lastTimestamp
```

```bash
kubectl get events -A --field-selector type=Warning --sort-by=.lastTimestamp
```

```bash
kubectl -n staging describe deployment product-service
```

```bash
kubectl -n staging describe pods -l app=product-service
```

## 8. Deployment progress during Stage 05 rolling update

```bash
kubectl -n staging rollout status deployment/product-service --timeout=240s
```

```bash
kubectl -n staging rollout history deployment/product-service
```

```bash
watch -n 1 'kubectl -n staging get pods -l app=product-service -o wide'
```

```bash
kubectl -n staging get deployment product-service -o jsonpath='{.spec.strategy}{"\n"}'
```

Check HTTP readiness from the Azure VM using the published NodePort:

```bash
curl -i --max-time 10 http://127.0.0.1:30082/actuator/health
```

## 9. Optional changes — WARNING: these modify Kubernetes

The **preferred way** to switch workshop stages is the numbered GitHub Actions pipelines, which include preflight and validation. Only run the commands below intentionally, outside a pipeline.

Scale product-service to 3 pods (Stage 03 concept):

```bash
kubectl -n staging scale deployment/product-service --replicas=3
```

Return product-service to 1 pod:

```bash
kubectl -n staging scale deployment/product-service --replicas=1
```

Restart a single application Deployment:

```bash
kubectl -n staging rollout restart deployment/product-service
```

> **Important:** Avoid `kubectl apply -k k8s/overlays/azure` during a live workshop. The baseline staging manifests declare zero application replicas and can shut down running applications. Do not run broad `kubectl delete`, `kubectl reset` or namespace deletion commands while teaching.

## 10. Workshop stage reference

| GitHub Actions | Desired monolith | Desired user | Desired product | Desired order | Key observation |
| --- | ---: | ---: | ---: | ---: | --- |
| 01 · Monolith | 1 | 0 | 0 | 0 | Single application |
| 02 · Microservices | 0 | 1 | 1 | 1 | Three services running |
| 03 · Manual scaling | 0 | 1 | 3 | 1 | gRPC calls may favor one product pod |
| 04 · gRPC correction | 0 | 1 | 3 | 1 | Check new order image and headless gRPC target |
| 05 · Rolling update | 0 | 1 | 3 | 1 | Watch product image, readiness and old/new pods |

> Replica counts show **desired** configuration; always compare them with **available/ready** replicas and check HTTP health. Stages 04 and 05 cannot be identified by replica count alone.
