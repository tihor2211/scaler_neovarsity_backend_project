"""Draws the report's benchmark charts from the committed k6 results (median across runs, as in the
summary tables).

    python charts.py build/charts

Colours: validated categorical slots (blue = with the optimisation / after, orange = without / before)
and the reserved status red for overselling, always paired with a text label.
"""
import json
import statistics
import sys
from collections import defaultdict
from pathlib import Path

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt  # noqa: E402

RESULTS = Path(__file__).resolve().parent.parent / "load-tests" / "results"
OUT = Path(sys.argv[1] if len(sys.argv) > 1 else "build/charts")

BLUE, ORANGE, CRITICAL = "#2a78d6", "#eb6834", "#d03b3b"
INK, INK_2, MUTED, GRID, BASELINE = "#0b0b0b", "#52514e", "#898781", "#e1e0d9", "#c3c2b7"
SAFE = ["OPTIMISTIC", "PESSIMISTIC", "CONDITIONAL_UPDATE", "REDIS"]
ALL = ["NAIVE"] + SAFE
LABEL = {"NAIVE": "Naive", "OPTIMISTIC": "Optimistic", "PESSIMISTIC": "Pessimistic",
         "CONDITIONAL_UPDATE": "Conditional update", "REDIS": "Redis Lua"}

plt.rcParams.update({
    "font.family": ["Helvetica", "Arial", "DejaVu Sans"],
    "font.size": 9,
    "text.color": INK,
    "axes.labelcolor": INK_2,
    "axes.edgecolor": BASELINE,
    "xtick.color": MUTED,
    "ytick.color": INK,
    "xtick.labelcolor": INK_2,
    "figure.dpi": 200,
    "savefig.dpi": 200,
    "savefig.bbox": "tight",
    "savefig.pad_inches": 0.08,
})


def rush(name):
    runs = defaultdict(list)
    for path in sorted((RESULTS / name).glob("*.json")):
        result = json.loads(path.read_text())
        runs[result["strategy"]].append(result)
    return runs


def median(runs, key):
    return statistics.median(key(r) for r in runs)


def style(ax, xlabel):
    ax.set_xlabel(xlabel)
    ax.grid(axis="x", color=GRID, linewidth=0.8)
    ax.set_axisbelow(True)
    for side in ("top", "right", "left"):
        ax.spines[side].set_visible(False)
    ax.spines["bottom"].set_color(BASELINE)
    ax.tick_params(axis="y", length=0)
    ax.tick_params(axis="x", length=0)


def units_sold():
    runs = rush("rush-2-lock-late")
    sold = [median(runs[s], lambda r: r["created"]) for s in ALL]
    stock = runs["NAIVE"][0]["stock"]
    fig, ax = plt.subplots(figsize=(6, 2.4))
    y = range(len(ALL))[::-1]
    colours = [CRITICAL if s == "NAIVE" else BLUE for s in ALL]
    ax.barh(list(y), sold, height=0.5, color=colours)
    ax.axvline(stock, color=INK_2, linewidth=1, linestyle=(0, (3, 2)))
    ax.text(stock + 6, len(ALL) - 0.45, f"stock: {stock} units", color=INK_2, fontsize=8, va="bottom")
    for yi, s, v in zip(y, ALL, sold):
        text = f"{v:.0f} sold: oversold by {v - stock:.0f}" if v > stock else f"{v:.0f} sold"
        ax.text(v + 6, yi, text, va="center", fontsize=8.5, color=INK)
    ax.set_yticks(list(y), [LABEL[s] for s in ALL])
    ax.set_xlim(0, max(sold) * 1.45)
    style(ax, f"Units sold to {runs['NAIVE'][0]['buyers']} simultaneous buyers (same in all 3 runs)")
    fig.savefig(OUT / "units-sold.png")
    plt.close(fig)


def lock_late():
    before, after = rush("rush-1-baseline"), rush("rush-2-lock-late")
    fig, axes = plt.subplots(1, 2, figsize=(6.4, 2.9), sharey=True)
    for ax, (key, title) in zip(axes, [("median", "Median checkout time"), ("p95", "95th percentile")]):
        b = [median(before[s], lambda r: r["latencyMs"][key]) for s in SAFE]
        a = [median(after[s], lambda r: r["latencyMs"][key]) for s in SAFE]
        y = list(range(len(SAFE)))[::-1]
        h = 0.36
        ax.barh([v + h / 2 + 0.02 for v in y], b, height=h, color=ORANGE, label="Reserve first (before)")
        ax.barh([v - h / 2 - 0.02 for v in y], a, height=h, color=BLUE, label="Lock late (after)")
        for yi, bv, av in zip(y, b, a):
            ax.text(bv + 15, yi + h / 2 + 0.02, f"{bv:.0f}", va="center", fontsize=7.5, color=INK_2)
            change = (av - bv) / bv * 100
            ax.text(av + 15, yi - h / 2 - 0.02, f"{av:.0f}  ({change:+.0f}%)".replace("-", "\u2212"), va="center",
                    fontsize=7.5,
                    color=INK)
        ax.set_title(title, fontsize=9, color=INK, loc="left")
        ax.set_xlim(0, 1700)
        style(ax, "milliseconds")
    axes[0].set_yticks(list(range(len(SAFE)))[::-1], [LABEL[s] for s in SAFE])
    handles, labels = axes[0].get_legend_handles_labels()
    fig.legend(handles, labels, loc="lower center", ncol=2, frameon=False, bbox_to_anchor=(0.55, -0.06))
    fig.subplots_adjust(bottom=0.25, wspace=0.08)
    fig.savefig(OUT / "lock-late.png")
    plt.close(fig)


def cache():
    runs = defaultdict(list)
    for path in sorted((RESULTS / "cache-off-vs-on").glob("*.json")):
        result = json.loads(path.read_text())
        runs[result["label"]].append(result)
    off, on = runs["cache-none"], runs["cache-redis"]
    fig, (left, right) = plt.subplots(1, 2, figsize=(6.4, 2.5), gridspec_kw={"width_ratios": [1, 1.5]})

    rps = [median(off, lambda r: r["requestsPerSec"]), median(on, lambda r: r["requestsPerSec"])]
    left.barh([1, 0], rps, height=0.36, color=[ORANGE, BLUE])
    left.set_ylim(-0.6, 1.6)
    for yi, v in zip([1, 0], rps):
        left.text(v + 250, yi, f"{v:,.0f}", va="center", fontsize=8, color=INK)
    left.set_yticks([1, 0], ["No cache", "Redis cache"])
    left.set_xlim(0, max(rps) * 1.3)
    left.set_title("Throughput", fontsize=9, loc="left")
    style(left, "requests per second")

    stats = [("median", "Median"), ("p95", "p95"), ("p99", "p99")]
    y = list(range(len(stats)))[::-1]
    h = 0.36
    o = [median(off, lambda r: r["latencyMs"][k]) for k, _ in stats]
    n = [median(on, lambda r: r["latencyMs"][k]) for k, _ in stats]
    right.barh([v + h / 2 + 0.02 for v in y], o, height=h, color=ORANGE, label="No cache")
    right.barh([v - h / 2 - 0.02 for v in y], n, height=h, color=BLUE, label="Redis cache")
    for yi, ov, nv in zip(y, o, n):
        right.text(ov + 2, yi + h / 2 + 0.02, f"{ov:.1f}", va="center", fontsize=7.5, color=INK_2)
        right.text(nv + 2, yi - h / 2 - 0.02, f"{nv:.1f}", va="center", fontsize=7.5, color=INK)
    right.set_yticks(y, [label for _, label in stats])
    right.set_xlim(0, max(o) * 1.25)
    right.set_title("Response time", fontsize=9, loc="left")
    style(right, "milliseconds")
    handles, labels = right.get_legend_handles_labels()
    fig.legend(handles, labels, loc="lower center", ncol=2, frameon=False, bbox_to_anchor=(0.55, -0.08))
    fig.subplots_adjust(wspace=0.35, bottom=0.25)
    fig.savefig(OUT / "cache.png")
    plt.close(fig)


if __name__ == "__main__":
    OUT.mkdir(parents=True, exist_ok=True)
    units_sold()
    lock_late()
    cache()
    print(f"Charts written to {OUT}")
