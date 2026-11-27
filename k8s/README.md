# Azure k3s workshop

The `k8s/overlays/azure` Kustomize overlay manages namespaces, PostgreSQL, Prometheus, Grafana, Loki, Fluent Bit and zero-replica Spring Boot staging Deployments.

**InfluxDB is not part of this cluster.** JMeter runs on participant laptops and produces JTL/HTML reports; Grafana reads Prometheus/Loki for server-side observations.

Use `kubectl kustomize k8s/overlays/azure` to render and `kubectl apply --dry-run=server -k k8s/overlays/azure` to validate. Deployment and credentials are described in `base/postgres`, `base/monitoring` and `base/staging` READMEs.

The `local-path` StorageClass has reclaim policy `Delete`: deleting PVCs can delete their data. Keep all credentials out of Git.
