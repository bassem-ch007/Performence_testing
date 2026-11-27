#!/usr/bin/env bash
# Azure k3s staging deploy; updates ONLY one existing deployment.
# Usage: bash scripts/workshop-deploy.sh order-service 1.1.0
set -Eeuo pipefail
# k3s kubectl defaults to the root-owned /etc/rancher/k3s/k3s.yaml even
# for noninteractive SSH sessions; use the per-user kubeconfig instead.
export KUBECONFIG="${HOME}/.kube/config"
if [[ ! -r "$KUBECONFIG" ]]; then
  echo "Kubernetes config is missing or unreadable: $KUBECONFIG" >&2
  exit 1
fi
if [[ $# -ne 2 ]]; then echo "Usage: $0 {monolith|user-service|product-service|order-service} SEMVER" >&2; exit 2; fi
service="$1"; tag="$2"
if [[ ! "$tag" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then echo "Invalid semver tag: $tag" >&2; exit 2; fi
case "$service" in
  monolith) deployment="ecommerce-mono"; container="ecommerce-mono"; image="bassem00/perf-monolith:$tag" ;;
  user-service|product-service|order-service) deployment="$service"; container="$service"; image="bassem00/perf-$service:$tag" ;;
  *) echo "Invalid service: $service" >&2; exit 2 ;;
esac
namespace=staging
kubectl -n "$namespace" get deployment "$deployment" >/dev/null
replicas="$(kubectl -n "$namespace" get deployment "$deployment" -o jsonpath='{.spec.replicas}')"
if [[ "$replicas" == "0" ]]; then echo "Refusing CD for inactive $deployment (0 replicas). Start target architecture first." >&2; exit 1; fi
previous="$(kubectl -n "$namespace" get deployment "$deployment" -o jsonpath="{.spec.template.spec.containers[?(@.name=='$container')].image}")"
echo "Deploying $image to $namespace/$deployment ($replicas replicas)"
echo "Previous image: $previous"
kubectl -n "$namespace" set image "deployment/$deployment" "$container=$image"
if ! kubectl -n "$namespace" rollout status "deployment/$deployment" --timeout=240s; then
  echo "Rollout failed. Restoring previous image: $previous" >&2
  kubectl -n "$namespace" set image "deployment/$deployment" "$container=$previous"
  kubectl -n "$namespace" rollout status "deployment/$deployment" --timeout=240s || true
  exit 1
fi
kubectl -n "$namespace" get deployment "$deployment" -o wide
kubectl -n "$namespace" get pods -l "app=$deployment" -o wide
echo "Deploy complete; confirm pod image digest separately before comparative testing."
