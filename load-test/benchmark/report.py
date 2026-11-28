"""Tạo bảng từ các lượt đo đã hoàn tất, bỏ qua lượt làm nóng rep=0."""

import collections
import itertools
import json
import sys
from pathlib import Path
from statistics import median

root = Path(sys.argv[1])
records = json.loads((root / "results.json").read_text())
trials = [r for r in records if r["rep"]]
modes = ("ATOMIC", "PESSIMISTIC", "OPTIMISTIC", "REDIS")
expected = set(itertools.product(modes, (20, 100, 200), (1, 2, 3), ("mono", "rest")))
keys = [(r["mode"], r["vus"], r["rep"], r["arch"]) for r in trials]
if set(keys) != expected or len(keys) != len(expected):
    raise SystemExit(f"Chưa đủ 72 lượt độc lập: {len(keys)}")
if any(r["verification"]["issues"] or r["verification"]["pending_ids"] or r["unmatched_202"] for r in trials):
    raise SystemExit("Có lượt chưa đối soát xong. Xem result.json trước khi lập bảng.")

print("""| Chế độ | VU | Kiến trúc | HTTP/s | Chấp nhận/s | Xác nhận/s (min–max) | HTTP p50/p95/p99 (ms) | HTTP ngoài 2xx | Tổng xác nhận / yêu cầu |
|---|---:|---|---:|---:|---:|---:|---:|---:|""")
for mode, vus, arch in itertools.product(modes, (20, 100, 200), ("mono", "rest")):
    group = [r for r in trials if (r["mode"], r["vus"], r["arch"]) == (mode, vus, arch)]
    rates = [r["confirmed_rps"] for r in group]
    latency = "/".join(f"{median(r['latency_ms'][q] for r in group):.0f}" for q in ("med", "p(95)", "p(99)"))
    total = sum(r["total"] for r in group)
    good = sum(int(n) for r in group for code, n in r["http"].items() if code.startswith("2"))
    print(
        f"| {mode} | {vus} | {arch} | {median(r['http_rps'] for r in group):.1f} | "
        f"{median(r['accepted_rps'] for r in group):.1f} | {median(rates):.1f} ({min(rates):.1f}–{max(rates):.1f}) | "
        f"{latency} | {(total - good) / total:.2%} | {sum(r['confirmed'] for r in group)} / {total} |"
    )
print(
    "\n| Kiến trúc | Mã HTTP, tổng 36 lượt | Trạng thái đã lưu | Số HTTP 202 | Từ 202 tới kết thúc p50/p95/max (ms) |"
)
print("|---|---|---|---:|---|")
for arch in ("mono", "rest"):
    group = [r for r in trials if r["arch"] == arch]
    codes = collections.Counter()
    states = collections.Counter()
    delays = []
    for r in group:
        codes.update(r["http"])
        states.update(r["outcomes"])
        delays.extend(r["accepted_to_terminal_ms"])
    delays.sort()
    import math

    lag = (
        "/".join(f"{delays[max(0, math.ceil(q * len(delays)) - 1)]:.0f}" for q in (0.5, 0.95, 1))
        if delays
        else "không có mẫu"
    )
    counts = lambda counter: ", ".join(f"{k}={v}" for k, v in sorted(counter.items()))
    print(f"| {arch} | {counts(codes)} | {counts(states)} | {codes['202']} | {lag} |")
