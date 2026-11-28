"""Chạy từ thư mục gốc sau khi Docker Compose đã sẵn sàng."""
import json
import os
import subprocess
import sys
import time
import uuid
from urllib.error import HTTPError
from urllib.request import Request, urlopen


def compose(*args):
    return subprocess.check_output(["docker", "compose", *args], text=True).strip()


def request(path, body=None, key=None, expected=200):
    headers = {"Content-Type": "application/json"}
    if key:
        headers["Idempotency-Key"] = key
    data = None if body is None else json.dumps(body).encode()
    req = Request(base + path, data=data, headers=headers)
    try:
        response = urlopen(req, timeout=30)
    except HTTPError as error:
        response = error
    with response:
        result = response.read().decode()
        if response.status != expected:
            raise AssertionError(f"{path}: HTTP {response.status}, expected {expected}: {result}")
        return json.loads(result)


def sql(service, query):
    return compose("exec", "-T", service, "sh", "-ec",
                   'exec psql -XAt -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "$1"',
                   "sh", query)


base = "http://" + compose("port", "order-service", "8080")
key = str(uuid.uuid4())
product = request("/api/v1/products", {"name": "smoke-" + key, "price": 10, "initialStock": 3},
                  expected=201)
pid = int(product["id"])
purchase = {"productId": pid, "quantity": 2, "lockMode": os.getenv("INVENTORY_LOCK_MODE", "ATOMIC")}
created = request("/api/v1/orders", purchase, key, 201)
oid = int(created["id"])
assert created["orderStatus"] == "CONFIRMED", created
assert request("/api/v1/orders", purchase, key)["id"] == oid
assert request(f"/api/v1/orders/{oid}")["orderStatus"] == "CONFIRMED"
request("/api/v1/orders", dict(purchase, quantity=1), key, 409)
request("/api/v1/orders", purchase, str(uuid.uuid4()), 409)
order_states = sql("order-postgres",
                   f"SELECT order_status FROM orders WHERE product_id={pid} ORDER BY id")
reservations = sql("inventory-postgres",
                   f"SELECT order_id, status FROM stock_reservations WHERE product_id={pid} ORDER BY order_id")
expected_reservations = sql("order-postgres",
                            f"SELECT id, CASE order_status WHEN 'CONFIRMED' THEN 'RESERVED' ELSE order_status END "
                            f"FROM orders WHERE product_id={pid} ORDER BY id")
stock = sql("inventory-postgres", f"SELECT stock FROM inventory WHERE product_id={pid}")
assert order_states.splitlines() == ["CONFIRMED", "REJECTED"], order_states
assert reservations == expected_reservations, (reservations, expected_reservations)
assert stock == "1", stock
print(f"product={pid}; orders={order_states.splitlines()}; reservations={reservations.splitlines()}; stock={stock}")

if "--recovery" in sys.argv:
    # Chỉ chạy trên project kiểm thử: tạm dừng cả hai phiên bản Inventory.
    try:
        compose("stop", "inventory-a", "inventory-b")
        pending = request("/api/v1/orders", dict(purchase, quantity=1), str(uuid.uuid4()), 202)
        pending_id = int(pending["id"])
        assert pending["orderStatus"] == "PENDING", pending
        assert sql("order-postgres", f"SELECT order_status FROM orders WHERE id={pending_id}") == "PENDING"
        assert sql("inventory-postgres",
                   f"SELECT count(*) FROM stock_reservations WHERE order_id={pending_id}") == "0"
        compose("restart", "order-service")
    finally:
        compose("up", "--no-build", "-d", "--wait", "inventory-a", "inventory-b")
    compose("up", "--no-build", "-d", "--wait", "order-service")
    deadline = time.monotonic() + 90
    while request(f"/api/v1/orders/{pending_id}")["orderStatus"] == "PENDING":
        if time.monotonic() >= deadline:
            raise AssertionError(f"Đơn {pending_id} vẫn đang chờ sau khi Inventory đã hoạt động trở lại")
        time.sleep(1)
    recovered = sql("order-postgres", f"SELECT order_status FROM orders WHERE id={pending_id}")
    reservation = sql("inventory-postgres",
                      f"SELECT status FROM stock_reservations WHERE order_id={pending_id}")
    stock = sql("inventory-postgres", f"SELECT stock FROM inventory WHERE product_id={pid}")
    assert (recovered, reservation, stock) == ("CONFIRMED", "RESERVED", "0"), (recovered, reservation, stock)
    print(f"restarted order={pending_id}; order={recovered}; reservation={reservation}; stock={stock}")
