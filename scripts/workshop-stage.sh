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
ready() { kubectl -n "$ns" rollout status "deployment/$1" --timeout=240s; }
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
    [[ "$(getreplicas user-service)" == 1 && "$(getreplicas order-service)" == 1 ]] || { echo "First run Stage 02" >&2; exit 1; }
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
    [[ "$(getreplicas product-service)" == 3 && "$(getreplicas order-service)" == 1 ]] || { echo "First run Stage 03" >&2; exit 1; }
    kubectl -n "$ns" get svc product-service-grpc-headless >/dev/null
    before="$(getimage order-service)"
    oldtarget="$(kubectl -n "$ns" get deployment order-service -o jsonpath='{.spec.template.spec.containers[0].env[?(@.name=="PRODUCT_GRPC_TARGET")].value}')"
    # one rollout (image + env) by applying a JSON merge patch.
    patch="$(printf '{"spec":{"template":{"spec":{"containers":[{"name":"order-service","image":"bassem00/perf-order-service:%s","env":[{"name":"PRODUCT_GRPC_TARGET","value":"%s"},{"name":"GRPC_LOAD_BALANCING_POLICY","value":"round_robin"}]}]}}}}' "$tag" "$corrected_target")"
    # Strategic merge patch avoids replacing other container env settings.
    kubectl -n "$ns" patch deployment order-service --type=strategic -p "$patch"
    if ! ready order-service; then
      echo "Corrected image failed; returning to baseline image and target" >&2
      kubectl -n "$ns" set image deployment/order-service "order-service=$before"
      kubectl -n "$ns" set env deployment/order-service "PRODUCT_GRPC_TARGET=$oldtarget"
      ready order-service || true
      exit 1
    fi
    health order-service 8083
    ;;
  05)
    echo "Stage 05: rolling product-service update from CI artifact"
    [[ "$tag" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ && "$tag" != 1.0.0 ]] || { echo "Specify a published new product image version" >&2; exit 2; }
    [[ "$(getreplicas product-service)" == 3 ]] || { echo "First run Stage 03 (and 04 for the full teaching sequence)" >&2; exit 1; }
    kubectl -n "$ns" patch deployment product-service --type=strategic -p '{"spec":{"strategy":{"type":"RollingUpdate","rollingUpdate":{"maxSurge":1,"maxUnavailable":0}}}}'
    before="$(getimage product-service)"
    kubectl -n "$ns" set image deployment/product-service "product-service=bassem00/perf-product-service:$tag"
    if ! ready product-service; then
      kubectl -n "$ns" set image deployment/product-service "product-service=$before"
      ready product-service || true
      exit 1
    fi
    health product-service 8082
    ;;
  *) echo "Usage: bash workshop-stage.sh 01|02|03|04 TAG|05 TAG" >&2; exit 2 ;;
esac
echo "Stage $stage completed"
kubectl -n "$ns" get deployment ecommerce-mono user-service product-service order-service -o wide
