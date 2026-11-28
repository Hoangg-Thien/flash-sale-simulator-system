"""Khởi động Inventory ATOMIC từ đầu trong khi tiến trình Redis không phục vụ."""

import json
import os
import subprocess
import tempfile
import uuid
from pathlib import Path

from restore_pair import http

project = "cold-redis-" + uuid.uuid4().hex[:10]
with tempfile.TemporaryDirectory() as tmp:
    override = Path(tmp) / "dead-redis.yml"
    override.write_text("""services:
  redis:
    entrypoint: ["sleep", "3600"]
    healthcheck:
      test: ["CMD", "redis-cli", "ping"]
      interval: 1s
      timeout: 1s
      retries: 2
  inventory-a:
    ports: ["127.0.0.1::8081"]
""")
    cmd = ["docker", "compose", "-p", project, "-f", "docker-compose.inventory.yml", "-f", str(override)]
    os.environ["INVENTORY_LOCK_MODE"] = "ATOMIC"
    try:
        subprocess.run([*cmd, "up", "--no-build", "-d", "redis"], check=True)
        subprocess.run([*cmd, "up", "--no-build", "--wait", "-d", "inventory-a"], check=True)
        addr = subprocess.check_output([*cmd, "port", "inventory-a", "8081"], text=True).strip()
        base = "http://" + addr + "/internal/v1/"
        assert http(base, "inventory/1/initialize", {"stock": 1})[0] == 200
        assert (
            http(base, "reservations", {"productId": 1, "quantity": 1, "orderId": 1})[1]["status"]
            == "RESERVED"
        )
        assert http(base, "inventory/1")[1]["stock"] == 0
        status = subprocess.check_output([*cmd, "ps", "--format", "{{.Health}}", "redis"], text=True).strip()
        assert status == "unhealthy", status
        print(json.dumps({"redis": status, "reservation": "RESERVED", "stock": 0, "passed": True}))
    finally:
        subprocess.run([*cmd, "down", "-v"], check=True)
