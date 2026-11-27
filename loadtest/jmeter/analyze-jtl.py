#!/usr/bin/env python3
"""Client-observed JMeter CSV JTL errors: HTTP 4xx/5xx/6xx, transport, assertions.
Usage: python3 loadtest/jmeter/analyze-jtl.py /path/to/results.jtl
Does not change JMeter plans or contact the Azure cluster.
"""
import argparse
import collections
import csv
import json
import math
import sys
from pathlib import Path

def group(code: str, success: bool) -> str:
    code = code.strip()
    try:
        n = int(code)
    except ValueError:
        return "transport/non-HTTP" if code else "missing-code"
    if 100 <= n < 400:
        return "assertion/functional" if not success else "HTTP 1xx-3xx"
    if 400 <= n < 500:
        return "HTTP 4xx"
    if 500 <= n < 600:
        return "HTTP 5xx"
    if 600 <= n < 700:
        return "nonstandard 6xx"
    return "other response code"

def percentile(sorted_values, fraction):
    if not sorted_values:
        return None
    at = (len(sorted_values) - 1) * fraction
    low = math.floor(at)
    high = math.ceil(at)
    return round(sorted_values[low] + (sorted_values[high] - sorted_values[low]) * (at - low), 1)

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("jtl", type=Path, help="JMeter CSV results.jtl")
    parser.add_argument("--json", type=Path, help="Optional JSON output")
    args = parser.parse_args()
    if not args.jtl.is_file():
        parser.error(f"File not found: {args.jtl}")

    outcomes = collections.Counter()
    codes = collections.Counter()
    failure_labels = collections.Counter()
    failures = 0
    count = 0
    elapsed = []
    sample_failures = []
    first = last = None

    try:
        with args.jtl.open(newline="", encoding="utf-8-sig") as fp:
            reader = csv.DictReader(fp)
            mandatory = {"timeStamp", "elapsed", "label", "responseCode", "success"}
            if not reader.fieldnames or not mandatory.issubset(reader.fieldnames):
                parser.error("Expected JMeter CSV JTL columns: " + ", ".join(sorted(mandatory)) +
                             ". Enable jmeter.save.saveservice.* CSV fields; XML JTL is unsupported.")
            for row in reader:
                if not row:
                    continue
                count += 1
                code = (row.get("responseCode") or "").strip()
                success = (row.get("success") or "").strip().lower() == "true"
                cls = group(code, success)
                outcomes[cls] += 1
                codes[code or "<empty>"] += 1
                if not success:
                    failures += 1
                    failure_labels[row.get("label") or "<unnamed>"] += 1
                    if len(sample_failures) < 12:
                        sample_failures.append({
                            "timeStamp": row.get("timeStamp"),
                            "label": row.get("label"),
                            "responseCode": code,
                            "responseMessage": (row.get("responseMessage") or "")[:160],
                            "failureMessage": (row.get("failureMessage") or "")[:160],
                        })
                try:
                    elapsed.append(float(row["elapsed"]))
                except (ValueError, TypeError):
                    pass
                try:
                    ts = int(row["timeStamp"])
                    first = ts if first is None else min(ts, first)
                    last = ts if last is None else max(ts, last)
                except (ValueError, TypeError):
                    pass
    except (OSError, UnicodeError, csv.Error) as exc:
        parser.error(str(exc))

    if count == 0:
        parser.error("JTL contains no samples")

    elapsed.sort()
    stats = {
        "source": str(args.jtl),
        "samples": count,
        "jmeter_failed": failures,
        "jmeter_failure_percent": round(100 * failures / count, 3),
        "response_categories_all_samples": dict(sorted(outcomes.items())),
        "response_codes_all_samples": dict(codes.most_common()),
        "failed_request_labels": dict(failure_labels.most_common()),
        "elapsed_ms": {
            "p50": percentile(elapsed, 0.50),
            "p95": percentile(elapsed, 0.95),
            "p99": percentile(elapsed, 0.99),
            "max": max(elapsed) if elapsed else None,
        },
        "first_sample_epoch_ms": first,
        "last_sample_epoch_ms": last,
        "example_failures": sample_failures,
    }
    print(f"JMeter samples: {count} | failures (success=false): {failures} ({stats['jmeter_failure_percent']}%)")
    print("Response categories (all samples; counts are NOT equivalent to failures):")
    for name, total in sorted(outcomes.items()):
        print(f"  {name:22} {total:>9}")
    print("Most frequent response codes:")
    for code, total in codes.most_common(12):
        print(f"  {code[:40]:40} {total:>8}")
    print("Failed request labels:")
    for name, total in failure_labels.most_common(12):
        print(f"  {name[:54]:54} {total:>8}")
    print(f"Latency: {stats['elapsed_ms']}")
    if sample_failures:
        print("Example failed samples (truncated):")
        for row in sample_failures[:8]:
            print("  " + json.dumps(row, ensure_ascii=False))
    if args.json:
        args.json.write_text(json.dumps(stats, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
        print(f"Saved: {args.json}")
    return 0 if failures == 0 else 1

if __name__ == "__main__":
    sys.exit(main())
