#!/usr/bin/env bash
# Only runs on the Azure VM. Actions transfers this script via pinned-host SSH.
set -Eeuo pipefail
# k3s kubectl defaults to the root-owned /etc/rancher/k3s/k3s.yaml even
# for noninteractive SSH sessions; use the per-user kubeconfig instead.
export KUBECONFIG="${HOME}/.kube/config"
if [[ ! -r "$KUBECONFIG" ]]; then
  echo "Kubernetes config is missing or unreadable: $KUBECONFIG" >&2
  exit 1
fi
stage="${1:-}"
tag="${2:-}"
ns=staging
baseline_order='bassem00/perf-order-service:1.0.0'
baseline_product='bassem00/perf-product-service:1.0.0'
corrected_target='dns:///product-service-grpc-headless:9092'
baseline_target='product-service:9092'
getreplicas() { kubectl -n "$ns" get deployment "$1" -o jsonpath='{.spec.replicas}'; }
getimage() { kubectl -n "$ns" get deployment "$1" -o jsonpath='{.spec.template.spec.containers[0].image}'; }
setreplicas() { kubectl -n "$ns" scale "deployment/$1" --replicas="$2"; }
ready() {
  kubectl -n "$ns" rollout status "deployment/$1" --timeout=240s
  local desired actual
  desired="$(getreplicas "$1")"
  actual="$(kubectl -n "$ns" get deployment "$1" -o jsonpath='{.status.availableReplicas}')"
  [[ "${actual:-0}" == "$desired" ]] || { echo "Not ready: $1 has ${actual:-0} of $desired available" >&2; return 1; }
}
assert_replicas() {
  [[ "$(getreplicas "$1")" == "$2" ]] || { echo "$1 expected $2 replica(s)" >&2; exit 1; }
}
health() {
  local service="$1" port="$2"
  kubectl -n "$ns" get service "$service" >/dev/null
  kubectl -n "$ns" run "health-$RANDOM" --rm -i --restart=Never \
    --image=curlimages/curl:8.12.1 -- \
    --fail --silent --show-error --max-time 12 "http://$service:$port/actuator/health" >/dev/null
}
# Every public stage prepares its own target state; no assumptions about prior stages.
# Stage 05-preflight intentionally restores a known old product image before CI.
baseline_user='bassem00/perf-user-service:1.0.0'
baseline_mono='bassem00/perf-monolith:1.0.0'

preflight() {
  kubectl -n data get statefulset postgres >/dev/null
  local dbready
  dbready="$(kubectl -n data get statefulset postgres -o jsonpath='{.status.readyReplicas}')"
  [[ "${dbready:-0}" -ge 1 ]] || { echo "PostgreSQL is not ready" >&2; exit 1; }
  kubectl -n "$ns" get secret app-postgres-auth >/dev/null
  for name in ecommerce-mono user-service product-service order-service; do
    kubectl -n "$ns" get deployment "$name" >/dev/null
    kubectl -n "$ns" get service "$name" >/dev/null
  done
}
setimage_if_changed() {
  local deployment="$1" image="$2"
  if [[ "$(getimage "$deployment")" != "$image" ]]; then
    kubectl -n "$ns" set image "deployment/$deployment" "$deployment=$image"
  fi
}
# Atomic strategic merge of the order image, gRPC target and policy. Other env
# entries (database secrets, metrics tags etc.) are retained by name.
setorder() {
  local image="$1" target="$2" policy="$3" patch
  patch="$(printf '{"spec":{"template":{"spec":{"containers":[{"name":"order-service","image":"%s","env":[{"name":"PRODUCT_GRPC_TARGET","value":"%s"},{"name":"GRPC_LOAD_BALANCING_POLICY","value":"%s"}]}]}}}}' "$image" "$target" "$policy")"
  kubectl -n "$ns" patch deployment order-service --type=strategic -p "$patch" >/dev/null
}
# From ANY prior architecture, build a healthy baseline microservices state.
# Replica counts and image tags are target values, not preconditions.
prepare_microservices() {
  local products="$1"
  preflight
  kubectl -n "$ns" scale deployment/ecommerce-mono --replicas=0
  setimage_if_changed user-service "$baseline_user"
  setimage_if_changed product-service "$baseline_product"
  setorder "$baseline_order" "$baseline_target" pick_first
  setreplicas user-service 1
  setreplicas product-service "$products"
  setreplicas order-service 1
  for dep in user-service product-service order-service; do ready "$dep"; done
  health user-service 8081
  health product-service 8082
  health order-service 8083
}
case "$stage" in
  01)
    echo "Stage 01: restore one baseline monolith; stop microservices"
    preflight
    setreplicas user-service 0
    setreplicas order-service 0
    setreplicas product-service 0
    setimage_if_changed ecommerce-mono "$baseline_mono"
    setreplicas ecommerce-mono 1
    ready ecommerce-mono
    health ecommerce-mono 8080
    ;;
  02)
    echo "Stage 02: independently prepare baseline microservices (1 product replica)"
    prepare_microservices 1
    ;;
  03)
    echo "Stage 03: independently prepare baseline microservices (3 product replicas)"
    prepare_microservices 3
    ;;
  04)
    echo "Stage 04: independently prepare corrected order-service with 3 baseline product replicas"
    [[ "$tag" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ && "$tag" != "1.0.0" ]] || { echo "Provide a published corrected order-service version" >&2; exit 2; }
    # Do not change the cluster if a required prerequisite is missing.
    kubectl -n "$ns" get svc product-service-grpc-headless >/dev/null
    prepare_microservices 3
    before="$(getimage order-service)"
    oldtarget="$(kubectl -n "$ns" get deployment order-service -o jsonpath='{.spec.template.spec.containers[0].env[?(@.name=="PRODUCT_GRPC_TARGET")].value}')"
    oldpolicy="$(kubectl -n "$ns" get deployment order-service -o jsonpath='{.spec.template.spec.containers[0].env[?(@.name=="GRPC_LOAD_BALANCING_POLICY")].value}')"
    setorder "bassem00/perf-order-service:$tag" "$corrected_target" round_robin
    if ! ready order-service || ! health order-service 8083; then
      echo "Stage 04 failed; restore previous working order-service image and settings" >&2
      setorder "$before" "$oldtarget" "${oldpolicy:-pick_first}"
      ready order-service || true
      exit 1
    fi
    ;;
  05-check)
    echo "Stage 05 preparation: independently restore 3 old product replicas before CI"
    prepare_microservices 3
    [[ "$(getimage product-service)" == "$baseline_product" ]] || { echo "Old product-service image not restored" >&2; exit 1; }
    echo "Stage 05 baseline is healthy and ready for CI"
    exit 0
    ;;
  05)
    echo "Stage 05: CI/CD rolls 3 old product replicas to a new image"
    [[ "$tag" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ && "$tag" != "1.0.0" ]] || { echo "Provide a new semver image tag" >&2; exit 2; }
    preflight
    [[ "$(getreplicas product-service)" == 3 ]] || { echo "Stage 05 baseline changed after preflight; aborting" >&2; exit 1; }
    [[ "$(getimage product-service)" == "$baseline_product" ]] || { echo "Stage 05 baseline image changed after preflight; aborting" >&2; exit 1; }
    for dep in user-service order-service product-service; do ready "$dep"; done
    kubectl -n "$ns" patch deployment product-service --type=strategic -p '{"spec":{"strategy":{"type":"RollingUpdate","rollingUpdate":{"maxSurge":1,"maxUnavailable":0}}}}'
    before="$(getimage product-service)"
    kubectl -n "$ns" set image deployment/product-service "product-service=bassem00/perf-product-service:$tag"
    if ! ready product-service || ! health product-service 8082; then
      echo "Stage 05 failed; restoring previous product-service image" >&2
      kubectl -n "$ns" set image deployment/product-service "product-service=$before"
      ready product-service || true
      exit 1
    fi
    ;;
  *) echo "Usage: bash workshop-stage.sh 01|02|03|04 TAG|05-check|05 TAG" >&2; exit 2 ;;
esac
echo "Stage $stage completed"
kubectl -n "$ns" get deployment ecommerce-mono user-service product-service order-service -o wide
