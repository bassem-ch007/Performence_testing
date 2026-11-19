#!/usr/bin/env bash
# Runs the same k6 workload against both architectures, each on a freshly started stack,
# then builds loadtest/results/comparison.md.
#   bash loadtest/run-comparison.sh                 # full load profile (~2 x 7 min)
#   TEST_PROFILE=smoke bash loadtest/run-comparison.sh
set -euo pipefail
cd "$(dirname "$0")/.."

TEST_PROFILE=${TEST_PROFILE:-load}
APPS="ecommerce-mono user-service product-service order-service"
RUNS=loadtest/results/runs.txt
mkdir -p loadtest/results
: > "$RUNS"

run() {
    local arch=$1
    echo ">>> [$arch] restarting a fresh stack"
    docker compose --profile "*" rm -f -s $APPS >/dev/null 2>&1 || true
    docker compose --profile "$arch" up -d --wait
    echo ">>> [$arch] letting the stack settle for 20 s"
    sleep 20
    local start end
    start=$(date +%s)
    docker compose run --rm -e ARCH="$arch" -e TEST_PROFILE="$TEST_PROFILE" k6
    end=$(date +%s)
    echo "$arch $TEST_PROFILE $start $end" >> "$RUNS"
    sleep 10   # let Prometheus scrape the tail of the run
}

run monolith
run microservices

python loadtest/report.py
