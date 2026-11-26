# Workshop v2 — gRPC client-side balancing across product-service pods

## Context

The original order-service keeps one shared gRPC ManagedChannel for each downstream service (see `GrpcClientConfig`). This is desirable for efficiency: we must **not** create a new channel for each HTTP request. However, when that channel resolves only a Kubernetes ClusterIP, it sees one virtual address and cannot rotate RPCs across individual pods. Kube-proxy may pin a long-lived HTTP/2 connection to one product-service pod.

This workshop contrasts **before (v1)** and **after (v2)** using the same application requests and replica counts.

## Change in v2

The order-service named channels in `order-service/src/main/resources/application.yaml` now explicitly configure `default.load-balancing-policy: round_robin`, overridable by `GRPC_LOAD_BALANCING_POLICY`. **Spring Boot 4.1.2 already defaults this setting to round_robin**; the real Kubernetes fix is to make the target resolve individual pod addresses.

A Kubernetes headless Service (`workshop/k8s/product-grpc-headless.yaml`) publishes multiple ready product-service pod endpoints. The order-service must target:

```
PRODUCT_GRPC_TARGET=dns:///product-service-grpc-headless:9092
GRPC_LOAD_BALANCING_POLICY=round_robin
```

Apply **only after** product-service Deployment exists and pods have `app: product-service` labels:

```bash
kubectl apply -f workshop/k8s/product-grpc-headless.yaml
kubectl get endpointslices -l kubernetes.io/service-name=product-service-grpc-headless -o wide
kubectl get pods -l app=product-service -o wide
```

Ensure there are 2+ **Ready** pod endpoints. DNS should resolve to multiple pod IPs; otherwise round-robin has nothing to distribute over.

### Suggested practical sequence

1. Deploy the original workshop app and populate a user and product.
2. Run the original order-service client target `dns:///product-service:9092` where `product-service` is a normal ClusterIP Service. Scale product-service to 3 replicas. Send repeated **order-service cart POSTs** to trigger gRPC product lookups. Observe backend activity using per-pod logs or metrics.
3. Apply the headless Service. Update the order-service Deployment environment variable `PRODUCT_GRPC_TARGET` to `dns:///product-service-grpc-headless:9092`, then perform a rolling restart. Repeat **the identical workload** and compare per-pod gRPC request counts.
4. Scale from 1 → 3 → 1, repeat traffic, watch pod readiness and request failures; then run a rolling update of product-service while traffic continues. Observe availability and convergence.

The normal `product-service` ClusterIP service can continue to serve REST requests. Only the order-service gRPC target needs the headless DNS name.

### Verification

```bash
kubectl get svc product-service product-service-grpc-headless
kubectl get pods -l app=product-service -o wide
kubectl get endpointslices -l kubernetes.io/service-name=product-service-grpc-headless -o wide
kubectl describe deployment order-service
kubectl rollout status deployment/order-service
```

Measure request counts by **product-service pod**, not just aggregated throughput. Prefer per-pod gRPC server metrics with stable pod labels. In a short test, equal distribution is not guaranteed, but traffic should reach multiple healthy endpoints. Measure errors and p95 latency during rollouts as well.

### Local Docker Compose

Keep the existing defaults and `docker-compose.yaml` unchanged. Docker Compose runs one product-service instance in the current profile, so enabling round-robin does **not** imply an observable balancing improvement there. Also note that `container_name` and fixed host ports block straightforward `docker compose up --scale product-service=3` using this file.

### Caveats

- Setting round-robin while still resolving the single ClusterIP address does **not** fix backend distribution.
- Do not replace the existing Service selector blindly: `app: product-service` must match the actual Deployment's pod labels.
- Endpoints must be Ready and serve the gRPC port 9092.
- DNS changes may not instantly rebalance existing channels; use a warmup period and check resolver behavior.
- The deployment manifests for PostgreSQL, the other applications, and metrics are outside this focused change. This is **not yet** a complete Kubernetes deployment.
- Round-robin per-RPC balancing can differ from HTTP load-balancing and does not guarantee identical utilization on every pod.
