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
# Health is checked through Kubernetes service DNS. Existing ready probes also protect rollouts.
case "$stage" in
  01)
    echo "Stage 01: monolith only; other services off"
    setreplicas user-service 0
    setreplicas product-service 0
    setreplicas order-service 0
    setreplicas ecommerce-mono 1
    ready ecommerce-mono
    health ecommerce-mono 8080
    ;;
  02)
    echo "Stage 02: microservices baseline, one replica each"
    # Fail before shutting down the monolith if shared dependencies are missing.
    kubectl -n data get statefulset postgres >/dev/null
    dbready="$(kubectl -n data get statefulset postgres -o jsonpath='{.status.readyReplicas}')"
    [[ "${dbready:-0}" -ge 1 ]] || { echo "PostgreSQL is not ready; keeping monolith running" >&2; exit 1; }
    kubectl -n "$ns" get secret app-postgres-auth >/dev/null
    for name in user-service product-service order-service; do
      kubectl -n "$ns" get deployment "$name" >/dev/null
      kubectl -n "$ns" get service "$name" >/dev/null
    done
    setreplicas ecommerce-mono 0
    # Return to known baseline for repeatability after stages 03/04/05.
    kubectl -n "$ns" set image deployment/order-service "order-service=$baseline_order"
    kubectl -n "$ns" set env deployment/order-service \
      "PRODUCT_GRPC_TARGET=$baseline_target" "GRPC_LOAD_BALANCING_POLICY=pick_first"
    kubectl -n "$ns" set image deployment/product-service "product-service=$baseline_product"
    setreplicas user-service 1
    setreplicas product-service 1
    setreplicas order-service 1
    for s in user-service product-service order-service; do ready "$s"; done
    health user-service 8081
    health product-service 8082
    health order-service 8083
    ;;
  03)
    echo "Stage 03: product-service 3 replicas, baseline gRPC order-service"
    [[ "$(getreplicas ecommerce-mono)" == 0 ]] || { echo "Stop monolith first (Stage 02)" >&2; exit 1; }
    assert_replicas user-service 1
    assert_replicas order-service 1
    assert_replicas product-service 1
    ready user-service
    ready order-service
    ready product-service
    [[ "$(getimage order-service)" == "$baseline_order" ]] || { echo "Order version is not 1.0.0; first run Stage 02" >&2; exit 1; }
    [[ "$(getimage product-service)" == "$baseline_product" ]] || { echo "Product version is not 1.0.0; first run Stage 02" >&2; exit 1; }
    target="$(kubectl -n "$ns" get deployment order-service -o jsonpath='{.spec.template.spec.containers[0].env[?(@.name=="PRODUCT_GRPC_TARGET")].value}')"
    [[ "$target" == "$baseline_target" ]] || { echo "Order-service is not using the baseline gRPC target; rerun Stage 02" >&2; exit 1; }
    setreplicas product-service 3
    ready product-service
    health product-service 8082
    ;;
  04)
    echo "Stage 04: deploy prebuilt corrected order-service with headless gRPC round-robin"
    [[ "$tag" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ && "$tag" != 1.0.0 ]] || { echo "Specify a published corrected version other than 1.0.0" >&2; exit 2; }
    assert_replicas ecommerce-mono 0
    assert_replicas product-service 3
    assert_replicas order-service 1
    assert_replicas user-service 1
    ready product-service
    ready order-service
    ready user-service
    [[ "$(getimage order-service)" == "$baseline_order" ]] || { echo "Stage 04 requires baseline order-service; run Stage 02 then 03" >&2; exit 1; }
    [[ "$(getimage product-service)" == "$baseline_product" ]] || { echo "Stage 04 requires baseline product-service; run Stage 02 then 03" >&2; exit 1; }
    kubectl -n "$ns" get svc product-service-grpc-headless >/dev/null
    before="$(getimage order-service)"
    oldtarget="$(kubectl -n "$ns" get deployment order-service -o jsonpath='{.spec.template.spec.containers[0].env[?(@.name=="PRODUCT_GRPC_TARGET")].value}')"
    oldpolicy="$(kubectl -n "$ns" get deployment order-service -o jsonpath='{.spec.template.spec.containers[0].env[?(@.name=="GRPC_LOAD_BALANCING_POLICY")].value}')"
    restore_order() {
      kubectl -n "$ns" set image deployment/order-service "order-service=$before"
      kubectl -n "$ns" set env deployment/order-service "PRODUCT_GRPC_TARGET=$oldtarget"
      if [[ -n "$oldpolicy" ]]; then
        kubectl -n "$ns" set env deployment/order-service "GRPC_LOAD_BALANCING_POLICY=$oldpolicy"
      else
        kubectl -n "$ns" set env deployment/order-service GRPC_LOAD_BALANCING_POLICY-
      fi
      ready order-service || true
    }
    # one rollout (image + env) by applying a JSON merge patch.
    patch="$(printf '{"spec":{"template":{"spec":{"containers":[{"name":"order-service","image":"bassem00/perf-order-service:%s","env":[{"name":"PRODUCT_GRPC_TARGET","value":"%s"},{"name":"GRPC_LOAD_BALANCING_POLICY","value":"round_robin"}]}]}}}}' "$tag" "$corrected_target")"
    # Strategic merge patch avoids replacing other container env settings.
    kubectl -n "$ns" patch deployment order-service --type=strategic -p "$patch"
    if ! ready order-service || ! health order-service 8083; then
      echo "Order-service rollout or health failed; restoring prior image and gRPC settings" >&2
      restore_order
      exit 1
    fi
    ;;
  05-check)
    echo "Stage 05 preflight: verify active microservices before CI"
    assert_replicas ecommerce-mono 0
    assert_replicas user-service 1
    assert_replicas order-service 1
    assert_replicas product-service 3
    [[ "$(getimage order-service)" != "$baseline_order" ]] || { echo "Stage 05 requires corrected order-service; run Stage 04 first" >&2; exit 1; }
    currenttarget="$(kubectl -n "$ns" get deployment order-service -o jsonpath='{.spec.template.spec.containers[0].env[?(@.name=="PRODUCT_GRPC_TARGET")].value}')"
    [[ "$currenttarget" == "$corrected_target" ]] || { echo "Order gRPC headless target not active; run Stage 04 first" >&2; exit 1; }
    ready product-service
    ready order-service
    health product-service 8082
    echo "Stage 05 preflight passed"
    exit 0
    ;;
  05)
    echo "Stage 05: rolling product-service update from CI artifact"
    [[ "$tag" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ && "$tag" != 1.0.0 ]] || { echo "Specify a published new product image version" >&2; exit 2; }
    assert_replicas ecommerce-mono 0
    assert_replicas user-service 1
    assert_replicas order-service 1
    assert_replicas product-service 3
    [[ "$(getimage order-service)" != "$baseline_order" ]] || { echo "Run Stage 04 before Stage 05" >&2; exit 1; }
    ready product-service
    ready order-service
    kubectl -n "$ns" patch deployment product-service --type=strategic -p '{"spec":{"strategy":{"type":"RollingUpdate","rollingUpdate":{"maxSurge":1,"maxUnavailable":0}}}}'
    before="$(getimage product-service)"
    kubectl -n "$ns" set image deployment/product-service "product-service=bassem00/perf-product-service:$tag"
    if ! ready product-service || ! health product-service 8082; then
      echo "Product rollout or health failed; restoring previous image" >&2
      kubectl -n "$ns" set image deployment/product-service "product-service=$before"
      ready product-service || true
      exit 1
    fi
    ;;
  *) echo "Usage: bash workshop-stage.sh 01|02|03|04 TAG|05 TAG" >&2; exit 2 ;;
esac
echo "Stage $stage completed"
kubectl -n "$ns" get deployment ecommerce-mono user-service product-service order-service -o wide
