"""Generates dashboards/architecture-comparison.json (run: python generate_dashboard.py)."""
import json
import pathlib

PROM = {"type": "prometheus", "uid": "prometheus"}
LOKI = {"type": "loki", "uid": "loki"}
MS = "user-service|product-service|order-service"
APP_FILTER = 'architecture=~"$architecture", uri!~"/actuator.*"'

panels = []
_id = 0
_y = 0


def row(title):
    global _id, _y
    _id += 1
    panels.append({"type": "row", "id": _id, "title": title, "collapsed": False,
                   "gridPos": {"h": 1, "w": 24, "x": 0, "y": _y}, "panels": []})
    _y += 1


def ts(title, targets, unit="short", x=0, w=12, h=8, ds=PROM, desc=""):
    global _id
    _id += 1
    panels.append({
        "type": "timeseries", "id": _id, "title": title, "description": desc, "datasource": ds,
        "gridPos": {"h": h, "w": w, "x": x, "y": _y},
        "fieldConfig": {"defaults": {"unit": unit, "custom": {"lineWidth": 2, "fillOpacity": 10}}, "overrides": []},
        "options": {"legend": {"displayMode": "table", "placement": "bottom", "calcs": ["mean", "max", "lastNotNull"]},
                    "tooltip": {"mode": "multi", "sort": "desc"}},
        "targets": [dict(datasource=ds, refId=chr(65 + i), **t) for i, t in enumerate(targets)],
    })


def next_line(h=8):
    global _y
    _y += h


def q(expr, legend):
    return {"expr": expr, "legendFormat": legend}


# ---------------------------------------------------------------- client side
row("Load test (k6, client side) - comparable end-to-end numbers")
ts("Throughput (requests/s)", [q("sum by (testid) (rate(k6_http_reqs_total[$__rate_interval]))", "{{testid}}")], "reqps", 0)
ts("Latency p95 / p99", [q("max by (testid) (k6_http_req_duration_p95)", "{{testid}} p95"),
                          q("max by (testid) (k6_http_req_duration_p99)", "{{testid}} p99")], "s", 12)
next_line()
ts("Error rate", [q("avg by (testid) (k6_http_req_failed_rate)", "{{testid}}")], "percentunit", 0, 8)
ts("Virtual users", [q("sum by (testid) (k6_vus)", "{{testid}}")], "short", 8, 8)
ts("p95 per endpoint", [q("max by (testid, name) (k6_http_req_duration_p95)", "{{testid}} {{name}}")], "s", 16, 8)
next_line()

# ---------------------------------------------------------------- server side
row("Spring Boot (server side)")
ts("HTTP requests/s per architecture",
   [q(f'sum by (architecture) (rate(http_server_requests_seconds_count{{{APP_FILTER}}}[$__rate_interval]))', "{{architecture}}")], "reqps", 0)
ts("HTTP p95 per architecture",
   [q(f'histogram_quantile(0.95, sum by (le, architecture) (rate(http_server_requests_seconds_bucket{{{APP_FILTER}}}[$__rate_interval])))', "{{architecture}}")], "s", 12)
next_line()
ts("HTTP p95 per endpoint",
   [q(f'histogram_quantile(0.95, sum by (le, application, method, uri) (rate(http_server_requests_seconds_bucket{{{APP_FILTER}}}[$__rate_interval])))', "{{application}} {{method}} {{uri}}")], "s", 0, 24, 9)
next_line(9)
ts("gRPC client calls/s (order-service -> user/product)",
   [q('sum by (rpc_service, rpc_method) (rate(grpc_client_seconds_count[$__rate_interval]))', "{{rpc_service}}/{{rpc_method}}")], "reqps", 0,
   desc="Extra network hops that only exist in the microservice architecture")
ts("gRPC client p95",
   [q('histogram_quantile(0.95, sum by (le, rpc_method) (rate(grpc_client_seconds_bucket[$__rate_interval])))', "{{rpc_method}}")], "s", 12)
next_line()
ts("HTTP 5xx/s", [q(f'sum by (application) (rate(http_server_requests_seconds_count{{{APP_FILTER}, status=~"5.."}}[$__rate_interval]))', "{{application}}")], "reqps", 0)
ts("DB pool: active connections", [q('sum by (application) (hikaricp_connections_active{architecture=~"$architecture"})', "{{application}}"),
                                    q('sum by (application) (hikaricp_connections_pending{architecture=~"$architecture"})', "{{application}} pending")], "short", 12)
next_line()

# ---------------------------------------------------------------- resources
row("Resources (cAdvisor / JVM / node-exporter)")
ts("CPU per architecture (cores)",
   [q('sum(rate(container_cpu_usage_seconds_total{name="ecommerce-mono"}[$__rate_interval]))', "monolith"),
    q(f'sum(rate(container_cpu_usage_seconds_total{{name=~"{MS}"}}[$__rate_interval]))', "microservices (total)"),
    q('sum(rate(container_cpu_usage_seconds_total{name="postgres_db"}[$__rate_interval]))', "postgres")], "short", 0)
ts("Memory per architecture (working set)",
   [q('sum(container_memory_working_set_bytes{name="ecommerce-mono"})', "monolith"),
    q(f'sum(container_memory_working_set_bytes{{name=~"{MS}"}})', "microservices (total)"),
    q('sum(container_memory_working_set_bytes{name="postgres_db"})', "postgres")], "bytes", 12)
next_line()
ts("CPU per container (cores)",
   [q('sum by (name) (rate(container_cpu_usage_seconds_total{name=~"ecommerce-mono|' + MS + '"}[$__rate_interval]))', "{{name}}")], "short", 0)
ts("JVM heap used", [q('sum by (application) (jvm_memory_used_bytes{area="heap", architecture=~"$architecture"})', "{{application}}")], "bytes", 12)
next_line()
ts("JVM GC pause time /s", [q('sum by (application) (rate(jvm_gc_pause_seconds_sum{architecture=~"$architecture"}[$__rate_interval]))', "{{application}}")], "s", 0)
ts("Host CPU usage (node-exporter)", [q('1 - avg(rate(node_cpu_seconds_total{mode="idle"}[$__rate_interval]))', "host")], "percentunit", 12)
next_line()

# ---------------------------------------------------------------- logs
row("Logs (Fluent Bit -> Loki)")
ts("Log lines/s per level",
   [q('sum by (level) (rate({job="spring-boot", architecture=~"$architecture"}[1m]))', "{{level}}")], "short", 0, 24, 6, LOKI)
next_line(6)
_id += 1
panels.append({"type": "logs", "id": _id, "title": "Application logs", "datasource": LOKI,
               "gridPos": {"h": 12, "w": 24, "x": 0, "y": _y},
               "options": {"showTime": True, "wrapLogMessage": True, "sortOrder": "Descending", "enableLogDetails": True},
               "targets": [{"datasource": LOKI, "refId": "A",
                            "expr": '{job="spring-boot", architecture=~"$architecture", level=~"$level"} | json | line_format "{{.container}} {{.level}} {{.logger_name}} - {{.message}}"'}]})

dashboard = {
    "uid": "architecture-comparison",
    "title": "Monolith vs Microservices",
    "tags": ["performance", "spring-boot"],
    "timezone": "browser",
    "schemaVersion": 41,
    "version": 1,
    "refresh": "5s",
    "time": {"from": "now-30m", "to": "now"},
    "templating": {"list": [
        {"name": "architecture", "label": "Architecture", "type": "custom", "query": "monolith,microservices",
         "includeAll": True, "multi": True, "allValue": ".*",
         "current": {"text": ["All"], "value": ["$__all"]},
         "options": []},
        {"name": "level", "label": "Log level", "type": "custom", "query": "ERROR,WARN,INFO,DEBUG",
         "includeAll": True, "multi": True, "allValue": ".*",
         "current": {"text": ["All"], "value": ["$__all"]}, "options": []},
    ]},
    "panels": panels,
}

out = pathlib.Path(__file__).parent / "dashboards" / "architecture-comparison.json"
out.parent.mkdir(parents=True, exist_ok=True)
out.write_text(json.dumps(dashboard, indent=2), encoding="utf-8")
print("wrote", out, len(panels), "panels")
