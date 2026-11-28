"""Số liệu HTTP và ảnh chụp hai DB theo từng lượt. Chạy từ gốc repo."""

import argparse
import collections
import gzip
import json
import os
import platform
import subprocess
import sys
import time
import uuid
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts/reliability"))
from verify_databases import verify

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("output", type=Path)
parser.add_argument("--seconds", type=int, default=10)
args = parser.parse_args()
out = args.output.resolve()
out.mkdir(parents=True, exist_ok=False)
project = "k6-" + uuid.uuid4().hex[:10]
compose = ["docker", "compose", "-p", project, "-f", "load-test/benchmark/compose.yml"]
opening = {str(i): 100000 for i in range(1, 33)}
results = []


def run(command, **kwargs):
    return subprocess.check_output(command, text=True, **kwargs).strip()


def sql(service, query):
    return run(
        [
            *compose,
            "exec",
            "-T",
            service,
            "psql",
            "-XqAt",
            "-v",
            "ON_ERROR_STOP=1",
            "-U",
            "bench",
            "-c",
            query,
        ]
    )


def rows(service, query):
    return json.loads(sql(service, f"SELECT coalesce(json_agg(t),'[]') FROM ({query}) t"))


def reset(arch):
    dist = arch == "rest"
    db = "order-db" if dist else "mono-db"
    sql(
        db,
        "TRUNCATE products RESTART IDENTITY CASCADE; INSERT INTO products(name,price) SELECT 'sku-' || i,10 FROM generate_series(1,32) i",
    )
    if dist:
        sql(
            "inventory-db",
            "TRUNCATE inventory,stock_reservations RESTART IDENTITY; INSERT INTO inventory(product_id,stock,initial_stock) SELECT i,100000,100000 FROM generate_series(1,32) i",
        )
    else:
        sql(db, "INSERT INTO inventory(product_id,stock) SELECT i,100000 FROM generate_series(1,32) i")
    run([*compose, "exec", "-T", "dist-redis" if dist else "mono-redis", "redis-cli", "FLUSHDB"])


def trial(arch, mode, vus, rep, seconds):
    folder = out / f"{mode}-{vus}-{rep}-{arch}"
    folder.mkdir()
    reset(arch)
    urls = "http://order:8080" if arch == "rest" else "http://mono-a:8080,http://mono-b:8080"
    with (folder / "console.txt").open("w") as log:
        subprocess.run(
            [
                "docker",
                "run",
                "--rm",
                "--user",
                f"{os.getuid()}:{os.getgid()}",
                "--network",
                project + "_default",
                "-v",
                f"{folder}:/out",
                "-v",
                f"{ROOT}/load-test/benchmark/purchase.js:/purchase.js:ro",
                "grafana/k6:1.0.0",
                "run",
                "--quiet",
                "--out",
                "json=/out/samples.json.gz",
                "-e",
                "URLS=" + urls,
                "-e",
                "MODE=" + mode,
                "-e",
                "KEY=trial",
                "-e",
                f"VUS={vus}",
                "-e",
                f"DURATION={seconds}s",
                "/purchase.js",
            ],
            check=True,
            stdout=log,
            stderr=log,
        )
    dist = arch == "rest"
    db = "order-db" if dist else "mono-db"
    deadline = time.monotonic() + 90
    while (
        dist
        and int(sql(db, "SELECT count(*) FROM orders WHERE order_status='PENDING'"))
        and time.monotonic() < deadline
    ):
        time.sleep(1)
    orders = rows(
        db,
        """SELECT o.id,o.product_id,o.idempotency_key,o.order_status,i.product_id AS item_product,i.quantity,
        extract(epoch from o.completed_at)*1000 AS completed_ms, """
        + ("o.cancel_requested" if dist else "false AS cancel_requested")
        + " FROM orders o LEFT JOIN order_items i ON o.id=i.order_id ORDER BY o.id",
    )
    stock = rows("inventory-db" if dist else db, "SELECT product_id,stock FROM inventory ORDER BY product_id")
    reservations = (
        rows(
            "inventory-db",
            "SELECT order_id,product_id,quantity,status FROM stock_reservations ORDER BY order_id",
        )
        if dist
        else []
    )
    data = {"orders": orders, "inventory": stock, "reservations": reservations}
    with gzip.open(folder / "database.json.gz", "wt") as f:
        json.dump(data, f, separators=(",", ":"))
    manifest = {"opening_stock": opening, "historical_ids": []}
    confirmed = [o for o in orders if o["order_status"] in ("SUCCESS", "CONFIRMED")]
    if dist:
        checked = verify(data, manifest)
    else:
        balance = {int(k): v for k, v in opening.items()}
        for o in confirmed:
            balance[o["product_id"]] -= o["quantity"]
        valid = (
            balance == {s["product_id"]: s["stock"] for s in stock}
            and min(balance.values()) >= 0
            and len(orders) == len({o["idempotency_key"] for o in orders})
        )
        checked = {
            "issues": [] if valid else ["stock/key mismatch"],
            "pending_ids": [o["id"] for o in orders if o["order_status"] == "PENDING"],
        }
    counts = collections.Counter()
    persisted = {o["idempotency_key"]: o for o in orders}
    delays = []
    seen = set()
    missing_commits = 0
    with gzip.open(folder / "samples.json.gz", "rt") as samples:
        for line in samples:
            sample = json.loads(line)
            if sample["type"] != "Point" or sample["metric"] != "purchase_outcomes":
                continue
            tags = sample["data"]["tags"]
            status = tags["code"]
            key = "trial-" + tags["sequence"]
            counts[status] += 1
            if key in seen:
                checked["issues"].append(f"duplicate workload key {key}")
            seen.add(key)
            order = persisted.get(key)
            if status in ("200", "201", "202"):
                if not order or str(order["id"]) != tags["order_id"]:
                    missing_commits += 1
                elif status == "202" and order["completed_ms"] is not None:
                    delays.append(max(0, order["completed_ms"] - int(tags["accepted_ms"])))
                elif status != "202" and order["order_status"] not in ("SUCCESS", "CONFIRMED"):
                    missing_commits += 1
            elif dist and status == "409" and (not order or order["order_status"] != "REJECTED"):
                missing_commits += 1
    if missing_commits or persisted.keys() - seen:
        checked["issues"].append("HTTP and persisted order IDs/outcomes disagree")
    metrics = json.loads((folder / "summary.json").read_text())
    if metrics["http_reqs"]["values"]["count"] != sum(counts.values()):
        checked["issues"].append("missing HTTP outcome sample")
    first = metrics["first_request_epoch_ms"]["values"]["min"]
    last = metrics["response_epoch_ms"]["values"]["max"]
    end = max([last] + [o["completed_ms"] for o in orders if o["completed_ms"] is not None])
    accepted = sum(counts[x] for x in ("200", "201", "202"))
    delays.sort()
    http_seconds = (last - first) / 1000
    completion_seconds = (end - first) / 1000
    total = sum(counts.values())
    remaining = sum(item["stock"] for item in stock)
    purchased_units = sum(order["quantity"] for order in confirmed)
    row = {
        "arch": arch,
        "mode": mode,
        "vus": vus,
        "rep": rep,
        "seconds": seconds,
        "http": dict(counts),
        "total": total,
        "accepted_rps": accepted / http_seconds,
        "confirmed_rps": len(confirmed) / completion_seconds,
        "http_rps": total / http_seconds,
        "error_rate": 1 - accepted / total,
        "outcomes": dict(collections.Counter(order["order_status"] for order in orders)),
        "confirmed": len(confirmed),
        "accepted_to_terminal_ms": delays,
        "unmatched_202": counts["202"] - len(delays),
        "latency_ms": metrics["http_req_duration"]["values"],
        "verification": checked,
        "stock_remaining": remaining,
        "balance_delta": sum(opening.values()) - purchased_units - remaining,
    }
    (folder / "result.json").write_text(json.dumps(row, indent=2))
    results.append(row)
    (out / "results.json").write_text(json.dumps(results, indent=2))
    print(folder.name, counts, len(confirmed), checked["issues"], flush=True)
    if (
        checked["issues"]
        or checked["pending_ids"]
        or row["balance_delta"]
        or not confirmed
        or row["unmatched_202"]
    ):
        raise ValueError(f"trial requires investigation: {folder}")


try:
    env = {
        "project": project,
        "duration_s": args.seconds,
        "host": platform.uname()._asdict(),
        "cpu": run(["lscpu"]),
        "memory": Path("/proc/meminfo").read_text().splitlines()[0],
        "docker": run(["docker", "version", "--format", "{{.Server.Version}}"]),
        "images": run(
            [
                "docker",
                "images",
                "--digests",
                "--no-trunc",
                "--format",
                "{{.Repository}} {{.Tag}} {{.Digest}} {{.ID}}",
            ]
        ),
        "baseline": run(["git", "rev-parse", "monolith-v1^{commit}"]),
        "head": run(["git", "rev-parse", "HEAD"]),
    }
    env["java"] = run(
        ["docker", "run", "--rm", "--entrypoint", "java", "flashsale-bench-order:working", "--version"]
    )
    env["k6"] = run(["docker", "run", "--rm", "grafana/k6:1.0.0", "version"])
    env["compose"] = run(["docker", "compose", "version"])
    env["warmup"] = {"vus": 100, "seconds": 15}
    (out / "environment.json").write_text(json.dumps(env, indent=2))
    for mode in ("ATOMIC", "PESSIMISTIC", "OPTIMISTIC", "REDIS"):
        os.environ["MODE"] = mode
        # Recreate every JVM and both data topologies for symmetric JIT/cache warm-up.
        run([*compose, "up", "-d", "--wait", "--force-recreate"])
        for arch in ("mono", "rest"):
            trial(arch, mode, 100, 0, 15)
        for vus in (20, 100, 200):
            for rep in (1, 2, 3):
                for arch in ("mono", "rest") if rep % 2 else ("rest", "mono"):
                    trial(arch, mode, vus, rep, args.seconds)
finally:
    subprocess.run([*compose, "down", "-v"], check=True)
