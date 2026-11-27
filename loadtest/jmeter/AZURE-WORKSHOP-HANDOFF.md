# Azure k3s workshop — JMeter collaborator handoff (27 Nov 2026)

## Scope and provenance
The six `.jmx` test plans and two architecture READMEs in `monolithic/` and `microservices/` were copied without modifications from `dhifaouiiyed-star/Performence_testing` for the workshop team's review and execution. This handoff and the two `azure-*.properties` files are the deployment-specific additions. No workload has been executed or validated end-to-end with these six JMX files on Azure yet.

## Infrastructure and public HTTP endpoints
One k3s node, namespace `staging`, Azure public IPv4 `20.240.55.238` (verify if VM public IP changes), unencrypted HTTP, exposed via NodePort. Do not use these ports to connect to PostgreSQL, gRPC, Prometheus or Grafana.

| App | HTTP base URL | Readiness check |
| --- | --- | --- |
| Monolith (only when scaled to 1) | http://20.240.55.238:30080 | /actuator/health |
| User | http://20.240.55.238:30081 | /actuator/health |
| Product | http://20.240.55.238:30082 | /actuator/health |
| Order | http://20.240.55.238:30083 | /actuator/health |

Product NodePort `30082` has been externally confirmed HTTP 200; others should be tested externally prior to the class. Azure NSG inbound rule admits selected NodePorts. Use synthetic data and close public inbound access after the exercise.

API contract:
- `GET/POST /api/v1/users` on user/monolith, `GET /api/v1/users/{id}`
- `GET/POST /api/v1/products`, `GET /api/v1/products/{id}`, `GET /api/v1/products/search?keyword=product` on product/monolith
- `POST /api/v1/carts` (JSON `{"productId":1,"quantity":2}`), `GET /api/v1/carts` on order/monolith, both with `X-User-ID: <existing user ID>`
- `POST /api/v1/orders` on order/monolith with `X-User-ID`; uses cart and gRPC within microservices.

The colleague's **six** JMX files use `host`, `port`, and (for microservices) `user_host`, `user_port`, `product_host`, `product_port`, `order_host`, `order_port`. These are **not** the dotted `user.host` / `user.port` settings in the separate older `ecommerce-load-test.jmx` plan.

## Run from repository root (JMeter CLI installed)
Each run must have a fresh results directory. JMeter CLI `-q` loads the matching Azure .properties file.

Monolith connectivity smoke (monolith **must** be running):
```bash
mkdir -p /tmp/workshop-mono-smoke
jmeter -n -t loadtest/jmeter/monolithic/load-test.jmx \
  -q loadtest/jmeter/azure-monolith.properties \
  -Jusers=3 -Jseed_users=3 -Jseed_products=3 -Jseed_threads=1 \
  -Jramp=3 -Jduration=30 -Jthink_time=500 \
  -Jrun_id=team1-mono-smoke \
  -l /tmp/workshop-mono-smoke/results.jtl \
  -e -o /tmp/workshop-mono-smoke/report
```

Microservices connectivity smoke (all three microservices **must** be running):
```bash
mkdir -p /tmp/workshop-micro-smoke
jmeter -n -t loadtest/jmeter/microservices/load-test.jmx \
  -q loadtest/jmeter/azure-microservices.properties \
  -Jusers=3 -Jseed_users=3 -Jseed_products=3 -Jseed_threads=1 \
  -Jramp=3 -Jduration=30 -Jthink_time=500 \
  -Jrun_id=team2-micro-smoke \
  -l /tmp/workshop-micro-smoke/results.jtl \
  -e -o /tmp/workshop-micro-smoke/report
```
Do not run default settings without planning capacity: load-test defaults to 1,500 concurrent shoppers, plus 1,500 seed users and 50 seed products. Spike/scalability test defaults similarly target high load and may overload the server or the laptops.

## Review of collaborator test plans
| Path | What it does | Fit / caution |
| --- | --- | --- |
| monolithic/load-test.jmx | Ramp to 1,500 users over 300 s, 300 s duration default | Good for monolith baseline; default is **not** a smoke test |
| monolithic/scalability-test.jmx | Five increasing thread-group stages of 300 users | Good for stepped capacity testing, **not** Kubernetes HPA |
| monolithic/spike-test.jmx | Base 50 users with 1,500-user spike | Good for burst/recovery; excessive for first test |
| microservices/load-test.jmx | Similar ramp, product-heavy workload | Good for microservice load and gRPC visibility |
| microservices/scalability-test.jmx | Five 300-user steps with product focus | Good for load steps, distinct from manual Kubernetes replica scaling |
| microservices/spike-test.jmx | Base 50 with 1,500-user spike, product focus | Good for stress/recovery; validate safe concurrency |

All six seed users and products via a setup thread group, collect generated IDs, and assert HTTP statuses. Seeding changes the shared PostgreSQL database and is not included in the timed main workload. Coordinate the five teams' runs; concurrent seeding/tests and leftover cart/order data can distort comparisons. Use unique run names and separate JTL/report folders.

**Critical comparison caveat:** monolith default mix = 80% browse / 15% detail / 5% order; microservices default = 65% browse / 15% detail / 20% order, with two additional product detail views in browse by default. These are intentionally different workloads. **Do not present their throughput/latency as an apples-to-apples monolith-vs-microservices result.** For a controlled architecture comparison, use the existing shared `ecommerce-load-test.jmx` with `monolith.properties` or `microservices.properties` and override host/port properties for Azure, OR align and validate the six plans' request mixes first. For gRPC imbalance demonstration keep the product-heavy microservices plan **identical** before and after fixing the client.

## Instructor — safe architecture switch (on Azure)
```bash
# Monolith baseline: stop microservices first
kubectl -n staging scale deployment/user-service deployment/product-service deployment/order-service --replicas=0
kubectl -n staging scale deployment/ecommerce-mono --replicas=1
kubectl -n staging rollout status deployment/ecommerce-mono

# Microservices baseline: stop monolith first
kubectl -n staging scale deployment/ecommerce-mono --replicas=0
kubectl -n staging scale deployment/user-service deployment/product-service deployment/order-service --replicas=1
kubectl -n staging rollout status deployment/user-service
kubectl -n staging rollout status deployment/product-service
kubectl -n staging rollout status deployment/order-service

# Team 3 manual scaling (only after microservices baseline)
kubectl -n staging scale deployment/product-service --replicas=3
kubectl -n staging rollout status deployment/product-service
```
The `k8s/base/staging` Deployment manifests intentionally declare `replicas: 0`; reapplying the whole Azure Kustomize overlay during a manual scaling experiment **will reset them to 0**. Apply only targeted manifests when updating monitoring/access.

## Observability & workshop sequence
Prometheus scrapes `/actuator/prometheus` on staging application pods; Grafana dashboards cover pod CPU, memory, HTTP and product-pod completed gRPC request counters (`grpc_server_seconds_count`). Always separate **direct product HTTP traffic** from **order-service → product-service gRPC** when interpreting replica distribution. The experiment is manual scaling 1 → 3 product pods on original order-service client, then deploying a separately built fixed order-service with DNS headless round-robin and re-running the **same** load. CI already builds/publishes versioned Docker Hub images; CD for prebuilt corrected order-service and product-service rolling update are pending.

## Known application caveat
Order-service version 1.0.0 has a response-mapping bug: `OrderItemDTO.subTotal` multiplies the cart line total by quantity a second time. The business response can show a valid order total but incorrect line subtotal. Track this separately from the gRPC load-balancing comparison.

## Ownership
Collaborator owns JMeter workload validation, safety of seed data, reporting, and reproducible scenario tuning. Instructor owns k3s namespace replicas, network rule, metrics, image builds and CD. Test runner should preserve JTL/HTML reports outside tracked repository history.
