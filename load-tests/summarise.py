"""Turns the per-run JSON files of a rush benchmark into a Markdown table (median across runs)."""
import json
import statistics
import sys
from collections import defaultdict
from pathlib import Path

ORDER = ["NAIVE", "OPTIMISTIC", "PESSIMISTIC", "CONDITIONAL_UPDATE", "REDIS"]


def main(results_dir: str) -> None:
    runs = defaultdict(list)
    for path in sorted(Path(results_dir).glob("*.json")):
        result = json.loads(path.read_text())
        runs[result["strategy"]].append(result)

    first = next(iter(runs.values()))[0]
    print(f"Rush: {first['buyers']} buyers, {first['stock']} units, "
          f"{len(next(iter(runs.values())))} runs per strategy (median shown; oversold shows every run)\n")
    print("| Strategy | Orders created | Rejected (409) | Errors | Oversold (each run) "
          "| Median ms | p95 ms | p99 ms | All served in ms | Orders/s |")
    print("|---|---|---|---|---|---|---|---|---|---|")
    for strategy in sorted(runs, key=lambda s: ORDER.index(s) if s in ORDER else 99):
        rs = runs[strategy]
        med = lambda f: statistics.median(f(r) for r in rs)
        print(f"| {strategy} | {med(lambda r: r['created']):.0f} | {med(lambda r: r['rejected']):.0f} "
              f"| {med(lambda r: r['failed']):.0f} | {', '.join(str(r['oversold']) for r in rs)} "
              f"| {med(lambda r: r['latencyMs']['median']):.0f} | {med(lambda r: r['latencyMs']['p95']):.0f} "
              f"| {med(lambda r: r['latencyMs']['p99']):.0f} | {med(lambda r: r['latencyMs']['max']):.0f} "
              f"| {med(lambda r: r['created'] / (r['latencyMs']['max'] / 1000)):.0f} |")


if __name__ == "__main__":
    main(sys.argv[1])
