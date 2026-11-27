# Performance testing workshop — Azure k3s

E-commerce backend implemented as a **Java/Spring Boot monolith** and **three microservices** (users, products, orders). The microservices use REST for callers and gRPC for internal lookups. The target environment is a single-node Azure k3s cluster; the five GitHub Actions workflows switch architectures and demonstrate scaling, gRPC balancing and rolling updates.

## Repository structure

| Path | Purpose |
| --- | --- |
| `ecommerceMono/` | Monolith application, Dockerfile, Maven build |
| `user-service/` | User microservice, gRPC server, Dockerfile |
| `product-service/` | Product microservice, gRPC server, Dockerfile |
| `order-service/` | Order microservice, gRPC client/server, Dockerfile |
| `k8s/base/staging/` | Application Deployments, Services and headless gRPC Service |
| `k8s/base/postgres/` | Stateful PostgreSQL storage and database resources |
| `k8s/base/monitoring/` | Grafana, Prometheus, Loki, Fluent Bit, dashboards |
| `k8s/overlays/azure/` | Azure k3s namespaces and kustomize composition |
| `k8s/workshop-access/` | NodePorts, GitHub Actions stages and instructor runbook |
| `.github/workflows/` | Five manual experiment stages, container CI, and CD |
| `scripts/` | Remote stage and deployment scripts used by Actions |
| `loadtest/jmeter/` | JMeter plans, property files and Azure smoke-test guide |
| `k3s commands.md` | Copy-paste kubectl and namespace command sheet |

## Workshop pipeline stages

| Pipeline | Monolith | User | Product | Order | Experiment |
| --- | ---: | ---: | ---: | ---: | --- |
| 01 — Monolith | 1 | 0 | 0 | 0 | Load and spike |
| 02 — Microservices | 0 | 1 | 1 | 1 | Load and spike |
| 03 — Manual scaling | 0 | 1 | 3 | 1 | Baseline gRPC distribution |
| 04 — gRPC correction | 0 | 1 | 3 | 1 | Corrected order image and headless target |
| 05 — CI/CD | 0 | 1 | 3 | 1 | Publish new product image and rolling update |

**Run the numbered workflows from GitHub Actions on `main`.** Before stage 04, build and publish the corrected order-service image. Stage 05 requires Docker Hub credentials and a new version tag.

## On the Azure VM

```bash
export KUBECONFIG="$HOME/.kube/config"
kubectl get namespaces
kubectl -n staging get deployments,pods,services
kubectl -n data get statefulsets,pods,pvc
kubectl -n monitoring get deployments,pods
```

The kubeconfig is intentionally local to the VM. For more commands, read [k3s commands.md](k3s%20commands.md).

## Running tests

Follow [the JMeter Azure workshop handoff](loadtest/jmeter/AZURE-WORKSHOP-HANDOFF.md). The tests run from participants' laptops against the published NodePorts. Use low-concurrency smoke tests before running load or spike tests.

## Operations and safety

- The three k3s namespaces are `staging`, `data`, and `monitoring`.
- Application Deployments in `k8s/base/staging/` start with **zero replicas**. Do not reapply the whole Azure overlay while stages are running; this could stop active workloads.
- Stage transitions **01 → 02** are intentional architecture cutovers, not rolling zero-downtime switches.
- The current Dockerfile-based application builds remain available to GitHub Actions even though the old local Docker Compose stack is no longer part of this Azure-focused repository.
- Historical JMeter output and old local k6 reports were removed from the active tree. The backup branch `backup/pre-workshop-cleanup-20261127` preserves the previous state.
- Follow [GitHub Actions stages](k8s/workshop-access/GITHUB-ACTIONS-STAGES.md) and the [instructor runbook](k8s/workshop-access/WORKSHOP-RUNBOOK.md) for launch sequence and limitations.
