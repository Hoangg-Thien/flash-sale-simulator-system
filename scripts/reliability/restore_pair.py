"""Diễn tập trên ba project Compose tạm, chỉ dùng dữ liệu tổng hợp."""

import concurrent.futures
import hashlib
import json
import os
import subprocess
import sys
import time
import uuid
from pathlib import Path
from urllib import error, request

from verify_databases import rows, snapshot, verify

TOKEN = uuid.uuid4().hex[:12]
OLD, SOURCE, RESTORED = (f"rehearse-{TOKEN}-{x}" for x in ("old", "source", "restore"))
os.environ["ORDER_PORT"] = "0"
LIMIT = 90
SERVICES = ("order-postgres", "inventory-postgres")


def compose(project, *args, **kwargs):
    file = "scripts/reliability/legacy.compose.yml" if project == OLD else "docker-compose.yml"
    return subprocess.run(
        ["docker", "compose", "-p", project, "-f", file, *args], check=True, stdout=subprocess.PIPE, **kwargs
    ).stdout


def url(project):
    name = "source-app" if project == OLD else "order-service"
    return "http://" + compose(project, "port", name, "8080").decode().strip() + "/api/v1/"


def http(base, path, body=None, key=None):
    headers = {"Content-Type": "application/json"}
    if key:
        headers["Idempotency-Key"] = key
    req = request.Request(base + path, None if body is None else json.dumps(body).encode(), headers)
    try:
        response = request.urlopen(req, timeout=30)
    except error.HTTPError as ex:
        response = ex
    with response:
        return response.status, json.load(response)


def dbtool(project, service, tool, dump=b""):
    return compose(
        project,
        "exec",
        "-T",
        service,
        "sh",
        "-ec",
        f'exec {tool} -U "$POSTGRES_USER" -d "$POSTGRES_DB"',
        input=dump,
    )


def fingerprints(project, service):
    objects = rows(
        project,
        service,
        """
        SELECT c.relname AS name, c.relkind AS kind FROM pg_class c
        JOIN pg_namespace n ON n.oid=c.relnamespace
        WHERE n.nspname='public' AND c.relkind IN ('r','S') ORDER BY c.relname""",
    )
    hashes = {}
    for obj in objects:
        quoted = '"' + obj["name"].replace('"', '""') + '"'
        query = f'SELECT to_jsonb(t) AS value FROM public.{quoted} t ORDER BY to_jsonb(t)::text COLLATE "C"'
        if obj["kind"] == "S":
            query = f"SELECT last_value, is_called FROM public.{quoted}"
        values = rows(project, service, query)
        encoded = json.dumps(values, sort_keys=True, separators=(",", ":")).encode()
        hashes[obj["name"]] = {"rows": len(values), "sha256": hashlib.sha256(encoded).hexdigest()}
    return hashes


def stop_writers(project):
    compose(project, "stop", "order-service")
    compose(project, "stop", "inventory-a", "inventory-b")


def run(report, backup_directory):
    compose(OLD, "up", "--no-build", "-d", "--wait")
    legacy = url(OLD)
    code, product = http(legacy, "products", {"name": "restore-fixture", "price": 12, "initialStock": 20})
    assert code == 201, product
    pid = product["id"]
    body = {"productId": pid, "quantity": 3, "lockMode": "ATOMIC"}
    code, historical = http(legacy, "orders", body, "historical")
    assert code == 201, historical
    compose(OLD, "stop", "source-app")
    opening = (
        compose(
            OLD,
            "exec",
            "-T",
            "source-db",
            "psql",
            "-U",
            "legacy",
            "-d",
            "flashsale",
            "-Atc",
            "SELECT stock FROM inventory",
        )
        .decode()
        .strip()
    )
    assert int(opening) == 17
    manifest = {"historical_ids": [historical["id"]], "opening_stock": {str(pid): int(opening)}}
    report["manifest"] = manifest
    compose(SOURCE, "up", "--no-build", "-d", "--wait", *SERVICES)
    dbtool(
        SOURCE,
        SERVICES[0],
        "pg_restore --single-transaction --no-owner --no-privileges",
        dbtool(OLD, "source-db", "pg_dump -Fc"),
    )
    compose(SOURCE, "up", "--no-build", "-d", "--wait")
    src = url(SOURCE)
    for _ in range(30):
        if http(src, f"products/{pid}")[0] == 200:
            break
        time.sleep(1)
    assert http(src, "orders", body, "historical")[0] == 200
    with concurrent.futures.ThreadPoolExecutor(16) as pool:
        duplicates = list(pool.map(lambda _: http(src, "orders", body, "same-key"), range(32)))
    assert all(c in (200, 201, 202) for c, _ in duplicates)
    assert len({o["id"] for _, o in duplicates}) == 1
    report["duplicate_http"] = [c for c, _ in duplicates]
    assert http(src, "orders", dict(body, quantity=40), "sold-out")[0] == 409
    compose(SOURCE, "stop", "inventory-a", "inventory-b")
    started = time.monotonic()
    code, waiting = http(src, "orders", body, "unresolved")
    assert code == 202
    stop_writers(SOURCE)
    before = snapshot(SOURCE)
    report["before"] = before
    checked = verify(before, manifest)
    assert not checked["issues"] and checked["pending_ids"] == [waiting["id"]], checked

    # No API, scheduler, admin client or database writer is active across BOTH dumps.
    report["public_data"] = {db: fingerprints(SOURCE, db) for db in SERVICES}
    backup_directory.mkdir(parents=True, exist_ok=False)
    report["dump_sha256"] = {}
    for db in SERVICES:
        blob = dbtool(SOURCE, db, "pg_dump -Fc")
        (backup_directory / f"{db}.dump").write_bytes(blob)
        report["dump_sha256"][db] = hashlib.sha256(blob).hexdigest()
    backup_manifest = dict(manifest, sha256=report["dump_sha256"])
    (backup_directory / "manifest.json").write_text(json.dumps(backup_manifest, indent=2) + "\n")
    compose(RESTORED, "up", "--no-build", "-d", "--wait", *SERVICES)
    for db in SERVICES:
        blob = (backup_directory / f"{db}.dump").read_bytes()
        assert hashlib.sha256(blob).hexdigest() == report["dump_sha256"][db]
        dbtool(
            RESTORED, db, "pg_restore --single-transaction --exit-on-error --no-owner --no-privileges", blob
        )
    for db in SERVICES:
        assert fingerprints(RESTORED, db) == report["public_data"][db], f"table or sequence mismatch in {db}"
    assert snapshot(RESTORED) == before, "restored logical snapshots differ before startup"
    report["identical_before_startup"] = True
    compose(RESTORED, "up", "--no-build", "-d", "--wait")
    dst = url(RESTORED)
    while time.monotonic() - started < LIMIT:
        if http(dst, f"orders/{waiting['id']}")[1]["orderStatus"] != "PENDING":
            break
        time.sleep(0.5)
    elapsed = time.monotonic() - started
    assert elapsed <= LIMIT, elapsed
    assert http(dst, "orders", body, "same-key")[0] == 200
    assert http(dst, "orders", body, "historical")[0] == 200
    stop_writers(RESTORED)
    after = snapshot(RESTORED)
    report.update(
        after=after, result=verify(after, manifest), elapsed_with_restore_s=elapsed, age_limit_s=LIMIT
    )
    ages = rows(
        RESTORED,
        SERVICES[0],
        f"SELECT extract(epoch FROM completed_at-requested_at) AS age FROM orders WHERE id={int(waiting['id'])}",
    )
    age = ages[0]["age"]
    report["pending_lifetime_s"] = age
    assert age is not None and age <= LIMIT
    assert not report["result"]["issues"] and not report["result"]["pending_ids"], report


if __name__ == "__main__":
    target = Path(sys.argv[1])
    report = {}
    try:
        run(report, target.parent / (target.stem + "-backup"))
        report["passed"] = True
    except Exception as ex:
        report.update(passed=False, error=repr(ex))
        raise
    finally:
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(json.dumps(report, indent=2) + "\n")
        for project in (OLD, SOURCE, RESTORED):
            compose(project, "down", "-v")
