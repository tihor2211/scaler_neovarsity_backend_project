#!/usr/bin/env bash
# Does a bigger database connection pool speed up the flash-sale rush? Runs the rush against one strategy
# with each Hikari pool size in turn, starting the app itself for each size, and saves the k6 results plus
# the app's connection-pool metrics (time spent waiting for a connection). Each start is followed by
# WARMUP unrecorded rushes: the first rushes on a fresh JVM are much slower while the JIT compiles.
#
#   ./load-tests/run-pool-experiment.sh                       # CONDITIONAL_UPDATE, pools 10 and 30, 3 runs
#   STRATEGY=PESSIMISTIC POOLS="10 20 40" ./load-tests/run-pool-experiment.sh
#
# Needs: MySQL, Redis and Kafka from docker compose (not the app container: this script runs the app on
# port 8080 itself), Java 21, k6. ADMIN_EMAIL / ADMIN_PASSWORD must match an existing admin, or a new one
# is seeded.
set -euo pipefail

cd "$(dirname "$0")/.."
STRATEGY=${STRATEGY:-CONDITIONAL_UPDATE}
POOLS=${POOLS:-"10 30"}
RUNS=${RUNS:-3}
WARMUP=${WARMUP:-2}   # unrecorded rushes after each start, so a cold JVM isn't measured
export ADMIN_EMAIL=${ADMIN_EMAIL:-admin@dashcart.local}
export ADMIN_PASSWORD=${ADMIN_PASSWORD:-admin12345}
export JAVA_HOME=${JAVA_HOME:-/opt/homebrew/opt/openjdk@21}
OUT_DIR=load-tests/results/pool-size-$(date +%Y%m%d-%H%M%S)
mkdir -p "$OUT_DIR"

./mvnw -q -DskipTests package
JAR=$(ls target/dashcart-*.jar | head -1)

for pool in $POOLS; do
  echo "== pool $pool"
  SPRING_DATASOURCE_HIKARI_MAXIMUMPOOLSIZE=$pool "$JAVA_HOME/bin/java" -jar "$JAR" > "target/app-pool-$pool.log" 2>&1 &
  app=$!
  trap 'kill $app 2>/dev/null || true' EXIT
  for _ in $(seq 1 60); do curl -sf localhost:8080/actuator/health > /dev/null && break; sleep 2; done

  for _ in $(seq 1 "$WARMUP"); do
    k6 run --quiet -e STRATEGY="$STRATEGY" load-tests/checkout-rush.js > /dev/null
  done
  for run in $(seq 1 "$RUNS"); do
    k6 run --quiet -e STRATEGY="$STRATEGY" -e OUT="$OUT_DIR/pool-$pool-$run.json" load-tests/checkout-rush.js
  done

  token=$(curl -s -H 'Content-Type: application/json' \
    -d "{\"email\":\"$ADMIN_EMAIL\",\"password\":\"$ADMIN_PASSWORD\"}" localhost:8080/auth/login \
    | python3 -c 'import json,sys; print(json.load(sys.stdin)["accessToken"])')
  for metric in hikaricp.connections.acquire hikaricp.connections.usage hikaricp.connections.max; do
    curl -s -H "Authorization: Bearer $token" "localhost:8080/actuator/metrics/$metric" \
      > "$OUT_DIR/metrics-pool-$pool-$metric.json"
  done
  kill $app
  wait $app 2>/dev/null || true
  trap - EXIT
done
echo "Results in $OUT_DIR"
