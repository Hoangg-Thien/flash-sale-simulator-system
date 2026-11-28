"""Đối chiếu trên cặp DB đã ngừng ghi, dùng tồn đầu kỳ và danh sách đơn cũ đã chốt."""

import argparse
import collections
import json
import subprocess
from pathlib import Path


def rows(project, service, query):
    sql = f"SELECT COALESCE(json_agg(t), '[]') FROM ({query}) t"
    command = [
        "docker",
        "compose",
        "-p",
        project,
        "exec",
        "-T",
        service,
        "sh",
        "-ec",
        'exec psql -XAt -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "$1"',
        "sh",
        sql,
    ]
    return json.loads(subprocess.check_output(command, text=True))


def snapshot(project):
    return {
        "orders": rows(
            project,
            "order-postgres",
            """
            SELECT o.id, o.product_id, o.idempotency_key, o.order_status, o.cancel_requested,
                   i.quantity, i.product_id AS item_product, i.unit_price, o.requested_at, o.completed_at
            FROM orders o LEFT JOIN order_items i ON i.order_id=o.id ORDER BY o.id,i.id""",
        ),
        "reservations": rows(
            project,
            "inventory-postgres",
            """
            SELECT order_id,product_id,quantity,status FROM stock_reservations ORDER BY order_id""",
        ),
        "inventory": rows(
            project,
            "inventory-postgres",
            """
            SELECT product_id,stock,version,initial_stock FROM inventory ORDER BY product_id""",
        ),
    }


def verify(data, manifest):
    issues, pending = [], []
    orders, reservations, keys = {}, {}, set()
    historical = set(manifest["historical_ids"])
    balances = {int(k): v for k, v in manifest["opening_stock"].items()}
    for row in data["orders"]:
        oid, key = row["id"], row["idempotency_key"]
        if oid in orders or (key is not None and key in keys):
            issues.append(f"duplicate order/item/key: {oid}")
        if oid not in historical and (not key or not key.strip()):
            issues.append(f"missing idempotency key: {oid}")
        orders[oid] = row
        keys.add(key)
        if not row["quantity"] or row["quantity"] < 1 or row["item_product"] != row["product_id"]:
            issues.append(f"invalid order item: {oid}")
    if historical - orders.keys():
        issues.append("missing historical orders")
    for r in data["reservations"]:
        oid, pid = r["order_id"], r["product_id"]
        order = orders.get(oid)
        if oid in reservations or oid in historical or not order:
            issues.append(f"unexpected reservation: {oid}")
        if r["status"] not in ("RESERVED", "REJECTED", "RELEASED"):
            issues.append(f"unknown reservation state: {oid}")
        reservations[oid] = r["status"]
        if not order or (order["product_id"], order["quantity"]) != (pid, r["quantity"]):
            issues.append(f"reservation payload mismatch: {oid}")
        if r["status"] == "RESERVED":
            balances[pid] = balances.get(pid, 0) - r["quantity"]
    for oid, o in orders.items():
        state, remote = o["order_status"], reservations.get(oid)
        if oid in historical:
            continue
        if state not in ("PENDING", "CONFIRMED", "REJECTED"):
            issues.append(f"unknown order state: {oid}")
        if state == "PENDING":
            pending.append(oid)
        elif (state == "CONFIRMED" and (remote != "RESERVED" or o["cancel_requested"])) or (
            state == "REJECTED" and remote not in ("RELEASED", "REJECTED")
        ):
            issues.append(f"terminal mismatch: {oid}/{state}/{remote}")
    actual = {s["product_id"]: s["stock"] for s in data["inventory"]}
    if actual != balances or any(n < 0 for n in actual.values()):
        issues.append(f"stock conservation: {actual} expected {balances}")
    return {
        "orders": dict(collections.Counter(x["order_status"] for x in orders.values())),
        "reservations": dict(collections.Counter(reservations.values())),
        "pending_ids": pending,
        "historical_count": len(historical),
        "stock": actual,
        "issues": issues,
    }


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("project")
    parser.add_argument("manifest", type=Path)
    parser.add_argument("--allow-pending", action="store_true")
    args = parser.parse_args()
    result = verify(snapshot(args.project), json.loads(args.manifest.read_text()))
    print(json.dumps(result, indent=2))
    raise SystemExit(bool(result["issues"] or (result["pending_ids"] and not args.allow_pending)))
