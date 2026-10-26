#!/usr/bin/env bash
# Product-listing benchmark with the Redis cache off and on. Starts the app itself for each setting
# (docker compose must be up and the jar built: ./mvnw -DskipTests package), RUNS times each.
#
#   ./load-tests/run-cache-benchmark.sh
set -euo pipefail

cd "$(dirname "$0")/.."
RUNS=${RUNS:-3}
JAVA=${JAVA_HOME:-/opt/homebrew/opt/openjdk@21}/bin/java
OUT_DIR=load-tests/results/cache-$(date +%Y%m%d-%H%M%S)
mkdir -p "$OUT_DIR"

for cache in none redis; do
  CACHE_TYPE=$cache "$JAVA" -jar target/dashcart-0.0.1-SNAPSHOT.jar > /dev/null 2>&1 &
  app=$!
  curl -s -o /dev/null --retry 30 --retry-delay 2 --retry-connrefused http://localhost:8080/actuator/health
  # Warm-up so JIT compilation and connection-pool start-up aren't measured.
  k6 run --quiet -e DURATION=10s -e LABEL=warmup load-tests/catalog-browse.js > /dev/null
  for run in $(seq 1 "$RUNS"); do
    k6 run --quiet -e LABEL="cache-$cache" -e OUT="$OUT_DIR/cache-$cache-$run.json" load-tests/catalog-browse.js
  done
  kill "$app"
  wait "$app" 2>/dev/null || true
done

python3 - "$OUT_DIR" <<'EOF' | tee "$OUT_DIR/summary.md"
import json, statistics, sys
from pathlib import Path
rows = {}
for p in sorted(Path(sys.argv[1]).glob("cache-*-*.json")):
    r = json.loads(p.read_text())
    rows.setdefault(r["label"], []).append(r)
print("Product listing, 100 virtual users for 30 s, median of runs\n")
print("| Setting | Requests/s | Median ms | p95 ms | p99 ms | Failed |")
print("|---|---|---|---|---|---|")
for label in ("cache-none", "cache-redis"):
    rs = rows.get(label, [])
    if not rs:
        continue
    med = lambda f: statistics.median(f(r) for r in rs)
    print(f"| {label} | {med(lambda r: r['requestsPerSec']):.0f} | {med(lambda r: r['latencyMs']['median']):.1f} "
          f"| {med(lambda r: r['latencyMs']['p95']):.1f} | {med(lambda r: r['latencyMs']['p99']):.1f} "
          f"| {med(lambda r: r['failedRate']) * 100:.2f}% |")
EOF
