#!/usr/bin/env bash
# Runs the flash-sale rush against every checkout strategy, RUNS times each, and writes a summary table.
# The app must already be running (with ADMIN_EMAIL / ADMIN_PASSWORD set) on BASE_URL.
#
#   ./load-tests/run-rush-benchmark.sh            # defaults: 500 buyers, 100 units, 3 runs
#   BUYERS=1000 STOCK=200 RUNS=5 ./load-tests/run-rush-benchmark.sh
set -euo pipefail

cd "$(dirname "$0")"
RUNS=${RUNS:-3}
STRATEGIES=${STRATEGIES:-"NAIVE OPTIMISTIC PESSIMISTIC CONDITIONAL_UPDATE REDIS"}
OUT_DIR=results/rush-$(date +%Y%m%d-%H%M%S)
mkdir -p "$OUT_DIR"

for strategy in $STRATEGIES; do
  for run in $(seq 1 "$RUNS"); do
    k6 run --quiet -e STRATEGY="$strategy" -e OUT="$OUT_DIR/$strategy-$run.json" checkout-rush.js
  done
done

python3 summarise.py "$OUT_DIR" | tee "$OUT_DIR/summary.md"
