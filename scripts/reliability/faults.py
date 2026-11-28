"""Dừng tiến trình tại ranh giới commit/HTTP, đo tuổi đơn bằng đồng hồ Order DB."""

import concurrent.futures
import json
import os
import shutil
import subprocess
import sys
import tempfile
import time
import uuid
from http.client import RemoteDisconnected
from pathlib import Path
from urllib.error import URLError

from restore_pair import http
from verify_databases import rows, snapshot, verify

project = "fault-" + uuid.uuid4().hex[:12]
command = [
    "docker",
    "compose",
    "-p",
    project,
    "-f",
    "docker-compose.yml",
    "-f",
    "scripts/reliability/fault.compose.yml",
]
nodes = ("inventory-a", "inventory-b")
report = {}
os.environ["ORDER_PORT"] = "0"


def dc(*args):
    return subprocess.check_output([*command, *args], text=True).strip()


def gates(op, enable):
    for node in nodes:
        dc(
            "exec",
            "-T",
            node,
            *(
                ["touch", "/tmp/barrier-" + op]
                if enable
                else ["rm", "-f", "/tmp/barrier-" + op, "/tmp/barrier-" + op + ".reached"]
            ),
        )


def reached(op):
    for _ in range(90):
        for node in nodes:
            value = dc("exec", "-T", node, "sh", "-c", f"cat /tmp/barrier-{op}.reached 2>/dev/null || true")
            if value:
                return node, int(value)
        time.sleep(0.1)
    raise TimeoutError(op)


def obs(oid):
    order = rows(
        project, "order-postgres", f"SELECT order_status,cancel_requested FROM orders WHERE id={oid}"
    )[0]
    remote = rows(
        project, "inventory-postgres", f"SELECT status FROM stock_reservations WHERE order_id={oid}"
    )
    return [order["order_status"], order["cancel_requested"], remote[0]["status"] if remote else None]


def terminal(oid, expected):
    for _ in range(100):
        state = obs(oid)
        if state[0] == expected:
            return state
        time.sleep(0.3)
    raise AssertionError(state)


def run():
    dc("up", "--no-build", "-d", "--wait")

    def url():
        return "http://" + dc("port", "order-service", "8080") + "/api/v1/"

    base = url()
    code, product = http(base, "products", {"name": "fault", "price": 10, "initialStock": 150})
    assert code == 201
    pid = product["id"]
    body = {"productId": pid, "quantity": 1}
    with concurrent.futures.ThreadPoolExecutor(16) as pool:
        for victim in ("inventory", "order"):
            gates("reserve", True)
            call = pool.submit(http, base, "orders", body, victim)
            node, oid = reached("reserve")
            before = obs(oid)
            assert before == ["PENDING", False, "RESERVED"], before
            dc("kill", "-s", "SIGKILL", node if victim == "inventory" else "order-service")
            if victim == "inventory":
                dc("start", node)
                dc("up", "--no-build", "-d", "--wait", node)
                # The second replica can have received the retry while the first restarted.
                gates("reserve", False)
                assert call.result()[0] in (201, 202)
            else:
                try:
                    call.result()
                    raise AssertionError("client received a reply from the terminated Order process")
                except (URLError, RemoteDisconnected, ConnectionError):
                    pass
                gates("reserve", False)
                dc("up", "--no-build", "-d", "--wait", "order-service")
                base = url()
            report[victim + "_kill"] = {
                "node": node,
                "order": oid,
                "before": before,
                "after": terminal(oid, "CONFIRMED"),
            }
        # First reconcile of this order must claim cancellation while reserve response is stalled.
        os.environ.update(PROBE_RECONCILE_AGE="3500", PROBE_CANCEL_AGE="3000")
        dc("up", "--no-build", "-d", "--wait", "order-service")
        base = url()
        gates("reserve", True)
        gates("release", True)
        call = pool.submit(http, base, "orders", body, "release-lost")
        _, oid = reached("reserve")
        release_node, released = reached("release")
        assert oid == released
        before = obs(oid)
        assert before == ["PENDING", True, "RELEASED"], before
        gates("reserve", False)
        assert call.result()[0] == 202
        dc("kill", "-s", "SIGKILL", release_node)
        dc("up", "--no-build", "-d", "--wait", release_node)
        gates("release", False)
        report["lost_release"] = {"order": oid, "before": before, "after": terminal(oid, "REJECTED")}
        os.environ.update(PROBE_RECONCILE_AGE="1000", PROBE_CANCEL_AGE="60000")
        dc("up", "--no-build", "-d", "--wait", "order-service")
        base = url()
        dc("stop", *nodes)
        start = time.monotonic()
        created = list(pool.map(lambda n: http(base, "orders", body, "backlog-" + str(n)), range(96)))
        assert all(code == 202 for code, _ in created)
        pending = rows(
            project,
            "order-postgres",
            "SELECT count(*) AS n, max(extract(epoch from now()-requested_at)) AS oldest_s FROM orders WHERE order_status='PENDING'",
        )[0]
        assert pending["n"] == 96
        # Hold the outage across several scheduler cycles, keeping all orders unresolved.
        outage_samples = [pending]
        for _ in range(3):
            time.sleep(2)
            sample = rows(
                project,
                "order-postgres",
                "SELECT count(*) AS n, max(extract(epoch from now()-requested_at)) AS oldest_s "
                "FROM orders WHERE order_status='PENDING'",
            )[0]
            assert sample["n"] == 96
            outage_samples.append(sample)
        dc("kill", "-s", "SIGKILL", "order-service")
        # Poll while applications start so the health wait cannot hide the drain curve.
        restoration = pool.submit(dc, "up", "--no-build", "-d", "--wait")
        samples = list(outage_samples)
        while time.monotonic() - start < 90:
            sample = rows(
                project,
                "order-postgres",
                "SELECT count(*) AS n, max(extract(epoch from now()-requested_at)) AS oldest_s FROM orders WHERE order_status='PENDING'",
            )[0]
            samples.append(sample)
            if sample["n"] == 0:
                break
            time.sleep(0.25)
        restoration.result()
        ages = rows(
            project,
            "order-postgres",
            "SELECT id,order_status,extract(epoch from completed_at-requested_at) AS age_s FROM orders WHERE idempotency_key LIKE 'backlog-%' ORDER BY id",
        )
        report["backlog"] = {"samples": samples, "orders": ages, "age_limit_s": 90}
        assert len(ages) == 96 and all(r["order_status"] == "CONFIRMED" and r["age_s"] <= 90 for r in ages)
    dc("stop", "order-service", *nodes)
    data = snapshot(project)
    check = verify(data, {"opening_stock": {str(pid): 150}, "historical_ids": []})
    report.update(verification=check, stock_rows=data["inventory"])
    assert check["issues"] == [] and check["pending_ids"] == [] and check["stock"] == {pid: 52}, check
    # 98 retained reservations and one reserve/release pair must account for exactly 100 writes.
    assert data["inventory"][0]["version"] == 100


if __name__ == "__main__":
    target = Path(sys.argv[1])
    with tempfile.TemporaryDirectory() as folder:
        path = Path("com/hthien/inventory/integration/CommitReplyBarrier.class")
        dest = Path(folder) / path
        dest.parent.mkdir(parents=True)
        shutil.copy(Path("services/inventory-service/target/test-classes") / path, dest)
        os.chmod(folder, 0o755)
        os.environ["BARRIER_CLASSES"] = folder
        try:
            run()
            report["passed"] = True
        except Exception as ex:
            report.update(passed=False, error=repr(ex))
            raise
        finally:
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(json.dumps(report, indent=2) + "\n")
            dc("down", "-v")
