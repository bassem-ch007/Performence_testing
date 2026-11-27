#!/usr/bin/env bash
# Read-only Azure k3s workshop status. Usage: bash scripts/workshop-status.sh
# Refresh: watch -n 3 -c 'bash scripts/workshop-status.sh'
set -uo pipefail
export KUBECONFIG="${KUBECONFIG:-$HOME/.kube/config}"
if [[ ! -r "$KUBECONFIG" ]]; then
  echo "ERROR: no readable kubeconfig at $KUBECONFIG" >&2
  echo "Use the Azure VM's ~/.kube/config (do not copy it to GitHub)." >&2
  exit 1
fi
if ! kubectl cluster-info >/dev/null 2>&1; then
  echo "ERROR: cannot reach Kubernetes using $KUBECONFIG" >&2
  exit 1
fi

section() { printf '\n\033[1;36m%s\033[0m\n' "=== $* ==="; }
ok() { printf '%-23s %s\n' "$1" "$2"; }
replicas() { kubectl -n staging get deployment "$1" -o jsonpath='{.spec.replicas}' 2>/dev/null || printf '?'; }
available() { kubectl -n staging get deployment "$1" -o jsonpath='{.status.availableReplicas}' 2>/dev/null; }
image() { kubectl -n staging get deployment "$1" -o jsonpath='{.spec.template.spec.containers[0].image}' 2>/dev/null || printf '?'; }
envvar() {
  local dep="$1" name="$2"
  kubectl -n staging get deployment "$dep" -o json 2>/dev/null |
    python3 -c 'import json,sys; data=json.load(sys.stdin); name=sys.argv[1]; print(next((x.get("value", "<secret>") for x in data["spec"]["template"]["spec"]["containers"][0].get("env", []) if x["name"]==name), "<unset>"))' "$name" 2>/dev/null || printf '?\n'
}
printf 'Workshop status | %s | cluster: ' "$(date '+%Y-%m-%d %H:%M:%S %Z')"
kubectl config current-context 2>/dev/null || true
section "01–05 | Active architecture / stage hint"
mono="$(replicas ecommerce-mono)"; user="$(replicas user-service)"
product="$(replicas product-service)"; order="$(replicas order-service)"
oi="$(image order-service)"; pi="$(image product-service)"
if [[ "$mono" == 1 && "$user" == 0 && "$product" == 0 && "$order" == 0 ]]; then
  stage="01 — MONOLITH baseline (Grafana 01)"
elif [[ "$mono" == 0 && "$user" == 1 && "$product" == 1 && "$order" == 1 ]]; then
  stage="02 — MICROSERVICES baseline (Grafana 02)"
elif [[ "$mono" == 0 && "$user" == 1 && "$product" == 3 && "$order" == 1 ]]; then
  if [[ "$oi" == *':1.0.0' ]]; then
    stage="03 — MANUAL SCALING, original order-service (Grafana 03)"
  elif [[ "$pi" == *':1.0.0' ]]; then
    stage="04 — gRPC correction candidate (Grafana 04)"
  else
    stage="05 — ROLLING UPDATE candidate (Grafana 05)"
  fi
else
  stage="MIXED / TRANSITION / CUSTOM (check Deployments)"
fi
echo "$stage"
echo "Stage hint is inferred from desired replicas and image tags; it is NOT proof of a successful rollout."
section "Deployments | desired/available and current image"
printf '%-20s %-11s %-11s %s\n' "DEPLOYMENT" "DESIRED" "AVAILABLE" "IMAGE"
for dep in ecommerce-mono user-service product-service order-service; do
  printf '%-20s %-11s %-11s %s\n' "$dep" "$(replicas "$dep")" "$(available "$dep")" "$(image "$dep")"
done
section "Application pods | ready, status, restarts, age, node"
kubectl -n staging get pods -o wide 2>&1 || true
section "Order gRPC settings | Stage 03 versus Stage 04"
ok "PRODUCT_GRPC_TARGET" "$(envvar order-service PRODUCT_GRPC_TARGET)"
ok "GRPC_LOAD_BALANCING_POLICY" "$(envvar order-service GRPC_LOAD_BALANCING_POLICY)"
section "Monitoring and PostgreSQL"
kubectl -n monitoring get deployment grafana prometheus loki 2>&1 || true
kubectl -n monitoring get daemonset fluent-bit 2>&1 || true
kubectl -n data get statefulset postgres 2>&1 || true
section "Live pod resources (kubectl top; may require metrics-server)"
kubectl -n staging top pods 2>&1 || true
section "Recent warning events (last 8, all namespaces)"
kubectl get events -A --field-selector type=Warning --sort-by=.lastTimestamp 2>/dev/null | tail -n 9 || true
section "Public endpoints (URLs; external network access not tested here)"
printf '%s\n' \
  "Monolith:    http://20.240.55.238:30080/actuator/health" \
  "User:        http://20.240.55.238:30081/actuator/health" \
  "Product:     http://20.240.55.238:30082/actuator/health" \
  "Order:       http://20.240.55.238:30083/actuator/health" \
  "Grafana:     http://20.240.55.238:30300" \
  "Prometheus:  http://20.240.55.238:30090"
printf '\nTip: bash scripts/workshop-status.sh | cat  (one snapshot)\n'
