"""Builds loadtest/results/comparison.md from the k6 summaries and Prometheus (resource usage).
Run from the repository root after run-comparison.sh:  python loadtest/report.py"""
import json
import pathlib
import urllib.parse
import urllib.request

RESULTS = pathlib.Path(__file__).parent / "results"
PROM = "http://localhost:9090"
CONTAINERS = {"monolith": 'name="ecommerce-mono"',
              "microservices": 'name=~"user-service|product-service|order-service"'}
ENDPOINTS = ["GET /products", "GET /products/{id}", "GET /products/search", "GET /users/{id}",
             "POST /carts", "GET /carts", "POST /orders"]


def prom(query, ts):
    url = f"{PROM}/api/v1/query?" + urllib.parse.urlencode({"query": query, "time": ts})
    with urllib.request.urlopen(url, timeout=10) as r:
        res = json.load(r)["data"]["result"]
    return float(res[0]["value"][1]) if res else float("nan")


def resources(arch, start, end):
    w = f"{end - start}s"
    sel = CONTAINERS[arch]
    cpu = f"sum(rate(container_cpu_usage_seconds_total{{{sel}}}[15s]))"
    mem = f"sum(container_memory_working_set_bytes{{{sel}}})"
    heap = f'sum(jvm_memory_used_bytes{{area="heap", architecture="{arch}"}})'
    pg_cpu = 'sum(rate(container_cpu_usage_seconds_total{name="postgres_db"}[15s]))'
    return {
        "cpu_avg": prom(f"avg_over_time(({cpu})[{w}:5s])", end),
        "cpu_max": prom(f"max_over_time(({cpu})[{w}:5s])", end),
        "mem_avg": prom(f"avg_over_time(({mem})[{w}:5s])", end) / 2**20,
        "mem_max": prom(f"max_over_time(({mem})[{w}:5s])", end) / 2**20,
        "heap_max": prom(f"max_over_time(({heap})[{w}:5s])", end) / 2**20,
        "pg_cpu_avg": prom(f"avg_over_time(({pg_cpu})[{w}:5s])", end),
        "grpc_calls": prom(f"sum(increase(grpc_client_seconds_count[{w}]))", end) if arch == "microservices" else 0.0,
    }


def k6(arch, profile):
    data = json.loads((RESULTS / f"{arch}-{profile}-summary.json").read_text(encoding="utf-8"))
    m = data["metrics"]
    dur = m.get("http_req_duration{expected_response:true}", m["http_req_duration"])["values"]
    out = {
        "reqs": m["http_reqs"]["values"]["count"],
        "rps": m["http_reqs"]["values"]["rate"],
        "iters": m["iterations"]["values"]["count"],
        "failed": m["http_req_failed"]["values"]["rate"] * 100,
        "lat": dur,
        "endpoints": {e: m[f"http_req_duration{{name:{e}}}"]["values"] for e in ENDPOINTS
                      if f"http_req_duration{{name:{e}}}" in m},
    }
    return out


def diff(a, b, lower_is_better=True):
    """relative difference of microservices (b) vs monolith (a)"""
    if not a:
        return "-"
    pct = (b - a) / a * 100
    better = (pct < 0) == lower_is_better
    return f"{pct:+.1f} % {'(better)' if better and abs(pct) >= 1 else '(worse)' if abs(pct) >= 1 else ''}".strip()


def main():
    runs = {}
    for line in (RESULTS / "runs.txt").read_text().splitlines():
        arch, profile, start, end = line.split()
        runs[arch] = dict(profile=profile, start=int(start), end=int(end))

    rows = {}
    for arch, r in runs.items():
        rows[arch] = {**k6(arch, r["profile"]), **resources(arch, r["start"], r["end"])}
    mo, ms = rows["monolith"], rows["microservices"]

    L = ["# Monolith vs microservices - load test comparison", "",
         f"Profile: `{runs['monolith']['profile']}` (same k6 workload, same total resource budget, fresh stack per run).", "",
         "## Throughput and latency (k6, client side)", "",
         "| Metric | Monolith | Microservices | Microservices vs monolith |", "|---|---:|---:|---|",
         f"| Requests | {mo['reqs']:.0f} | {ms['reqs']:.0f} | {diff(mo['reqs'], ms['reqs'], False)} |",
         f"| Throughput (req/s) | {mo['rps']:.1f} | {ms['rps']:.1f} | {diff(mo['rps'], ms['rps'], False)} |",
         f"| Completed user sessions | {mo['iters']:.0f} | {ms['iters']:.0f} | {diff(mo['iters'], ms['iters'], False)} |",
         f"| Failed requests | {mo['failed']:.2f} % | {ms['failed']:.2f} % | |"]
    for k, label in [("avg", "avg"), ("med", "median"), ("p(95)", "p95"), ("p(99)", "p99"), ("max", "max")]:
        L.append(f"| Latency {label} (ms) | {mo['lat'][k]:.1f} | {ms['lat'][k]:.1f} | {diff(mo['lat'][k], ms['lat'][k])} |")

    L += ["", "## p95 latency per endpoint (ms)", "",
          "| Endpoint | Monolith | Microservices | Microservices vs monolith |", "|---|---:|---:|---|"]
    for e in ENDPOINTS:
        a, b = mo["endpoints"].get(e), ms["endpoints"].get(e)
        if a and b:
            L.append(f"| `{e}` | {a['p(95)']:.1f} | {b['p(95)']:.1f} | {diff(a['p(95)'], b['p(95)'])} |")

    L += ["", "## Resource usage during the run (cAdvisor / Micrometer)", "",
          "| Metric | Monolith | Microservices (sum of 3) | Microservices vs monolith |", "|---|---:|---:|---|",
          f"| CPU avg (cores) | {mo['cpu_avg']:.2f} | {ms['cpu_avg']:.2f} | {diff(mo['cpu_avg'], ms['cpu_avg'])} |",
          f"| CPU max (cores) | {mo['cpu_max']:.2f} | {ms['cpu_max']:.2f} | {diff(mo['cpu_max'], ms['cpu_max'])} |",
          f"| Memory avg (MiB) | {mo['mem_avg']:.0f} | {ms['mem_avg']:.0f} | {diff(mo['mem_avg'], ms['mem_avg'])} |",
          f"| Memory max (MiB) | {mo['mem_max']:.0f} | {ms['mem_max']:.0f} | {diff(mo['mem_max'], ms['mem_max'])} |",
          f"| JVM heap max (MiB) | {mo['heap_max']:.0f} | {ms['heap_max']:.0f} | {diff(mo['heap_max'], ms['heap_max'])} |",
          f"| CPU per 1000 requests (core-s) | {mo['cpu_avg'] * (runs['monolith']['end'] - runs['monolith']['start']) / mo['reqs'] * 1000:.2f} "
          f"| {ms['cpu_avg'] * (runs['microservices']['end'] - runs['microservices']['start']) / ms['reqs'] * 1000:.2f} | |",
          f"| PostgreSQL CPU avg (cores) | {mo['pg_cpu_avg']:.2f} | {ms['pg_cpu_avg']:.2f} | |",
          f"| Internal gRPC calls | 0 | {ms['grpc_calls']:.0f} | |", ""]

    out = RESULTS / "comparison.md"
    out.write_text("\n".join(L), encoding="utf-8")
    print("\n".join(L))
    print(f"\nwritten to {out}")


if __name__ == "__main__":
    main()
