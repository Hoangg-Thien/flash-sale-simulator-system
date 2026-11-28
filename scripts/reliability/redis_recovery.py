"""Ảnh hưởng của sự cố Redis qua API mua hàng trong triển khai thực."""

import json
import os
import sys
import time
import uuid
from pathlib import Path

from restore_pair import compose, http, stop_writers, url
from verify_databases import snapshot, verify


def exercise(mode):
    project = "redis-fault-" + uuid.uuid4().hex[:12]
    os.environ["INVENTORY_LOCK_MODE"] = mode
    try:
        compose(project, "up", "--no-build", "-d", "--wait")
        base = url(project)
        code, product = http(base, "products", {"name": "outage", "price": 10, "initialStock": 5})
        assert code == 201
        pid = product["id"]
        body = {"productId": pid, "quantity": 2}
        manifest = {"historical_ids": [], "opening_stock": {str(pid): 5}}
        compose(project, "pause", "redis")
        try:
            code, order = http(base, "orders", body, "outage")
            before = snapshot(project)
            if mode == "ATOMIC":
                assert (code, before["inventory"][0]["stock"]) == (201, 3)
            else:
                assert (code, before["inventory"][0]["stock"]) == (202, 5)
                assert not before["reservations"]
            assert not verify(before, manifest)["issues"]
        finally:
            compose(project, "unpause", "redis")
        started = time.monotonic()
        while http(base, f"orders/{order['id']}")[1]["orderStatus"] == "PENDING":
            if time.monotonic() - started >= 90:
                raise TimeoutError("pending after redis recovery")
            time.sleep(0.5)
        assert http(base, "orders", body, "outage")[0] == 200
        stop_writers(project)
        after = snapshot(project)
        check = verify(after, manifest)
        assert check["stock"] == {pid: 3} and not check["issues"] and not check["pending_ids"]
        assert after["inventory"][0]["version"] == 1
        return {"http_during_outage": code, "before": before, "after": after, "verification": check}
    finally:
        compose(project, "down", "-v")


if __name__ == "__main__":
    report = {}
    try:
        for mode in ("ATOMIC", "REDIS"):
            report[mode] = exercise(mode)
        report["passed"] = True
    except Exception as ex:
        report.update(passed=False, error=repr(ex))
        raise
    finally:
        Path(sys.argv[1]).write_text(json.dumps(report, indent=2) + "\n")
