# Five independent GitHub Actions workshop stages

Each pipeline can run **from any previous application state** on the existing Azure k3s cluster. The engine reconciles replica counts, original application images and gRPC configuration rather than rejecting a run because a different stage ran first.

| Pipeline | Target state in staging | Behavior |
| --- | --- | --- |
| **01 — Monolith** | monolith 1; user/product/order 0 | Restores baseline monolith image `1.0.0`, starts one replica and verifies health |
| **02 — Microservices** | monolith 0; user/product/order 1/1/1 | Restores all original `1.0.0` images, original product gRPC target and `pick_first`, verifies all three |
| **03 — Manual scaling** | monolith 0; user/product/order 1/3/1 | Restores the same baseline images and gRPC target, ensures **three** product replicas even if previously 3, 1 or 0 |
| **04 — gRPC correction** | monolith 0; user/product/order 1/3/1 | Prepares baseline 3-product architecture, then deploys published corrected order image (default `1.1.0`) with headless DNS and `round_robin`; restores working baseline order image on failure |
| **05 — CI/CD rolling update** | monolith 0; user/product/order 1/3/1 | Its **05-preflight prepares** the original `product-service:1.0.0` with 3 ready replicas and baseline order/user; CI builds a new product image; CD rolls 3 old product replicas to the new version with HTTP continuity checks |

**No stage needs another stage to be run first.** Switching back from 04 to 03 deliberately restores `order-service:1.0.0`, `PRODUCT_GRPC_TARGET=product-service:9092`, `GRPC_LOAD_BALANCING_POLICY=pick_first`, and `product-service:1.0.0` at **3 replicas**, rather than erroring on prior versions or replica counts.

## Requirements shared by all five workflows

- GitHub workflow files on `main`.
- GitHub Environment `workshop-staging` with variables `WORKSHOP_SSH_HOST`, `WORKSHOP_SSH_USER`, `WORKSHOP_PUBLIC_IP` and secrets `WORKSHOP_SSH_PRIVATE_KEY`, `WORKSHOP_SSH_KNOWN_HOSTS`.
- GitHub runner SSH access to the VM and readable VM `~/.kube/config`.
- k3s cluster, PostgreSQL `data/postgres`, `staging/app-postgres-auth`, baseline service Deployments, and per-service Kubernetes Services already created.
- Container images `bassem00/perf-{monolith,user-service,product-service,order-service}:1.0.0` on Docker Hub.
- For Stage 04, a **published corrected** `bassem00/perf-order-service:1.1.0` (or another corrected tag), and the headless product-service gRPC Service.
- For Stage 05, repository Docker Hub secrets `DOCKERHUB_USERNAME` and `DOCKERHUB_TOKEN`, an **unused product-service version tag** (e.g. `1.1.1`) and network access to the public product health endpoint `30082`.

## Run each stage

GitHub → Actions → select one of `workshop-01.yaml` through `workshop-05.yaml` → **Run workflow** → choose `main`.

For Stage 04, enter the corrected *order-service* image tag. For Stage 05, enter a **new** *product-service* image tag and source ref.

### Stage 05: CI/CD under active JMeter load

Stage 05 starts with a **state-changing preflight**, unlike a read-only check: it makes the old baseline microservices architecture healthy first, ensuring the rolling deployment is genuinely from the old product image. Start the sustained JMeter test after this baseline is prepared and keep it running during CD. Because the full Stage 05 pipeline performs CI build between preparation and deployment, start or restart the JMeter workload in time for CD; use the GitHub Actions jobs to observe progress.

Deployment uses `maxSurge=1`, `maxUnavailable=0`, 3 product replicas and external periodic HTTP health probes. Check both the GitHub Actions summary and JMeter request error rate / percentiles to assess **observed** continuity. A green workflow is not a guarantee of zero business-request failures.

### Operational safeguards and limits

- **Do not run different stage pipelines simultaneously.** The shared SSH stage engine serializes its own jobs, but the Stage 05 build happens in a separate job between preparation and deployment, so another stage started during its build can change the cluster. The Stage 05 CD job checks that the intended old product image and replica count still exist and aborts if they changed.
- Stages 01–04 deliberately switch/reconcile architectures, potentially causing **temporary interruptions**. Only Stage 05's product rolling deployment is intended as a zero-downtime experiment.
- When returning from Stage 05, running Stage 03 intentionally restores the original product image; do not mistake this for accidental version rollback.
- Deployment state is reconciled through `kubectl`, **not** `kubectl apply -k k8s/overlays/azure`; the static baseline manifests specify zero replicas.
- Health probes test HTTP availability, not full gRPC and business logic. Compare actual traffic in JMeter and Grafana.
- These changes have been committed to `main`, but the new independent-state behavior still needs a live round-trip test (04 → 03 and 05 → 02/03).
