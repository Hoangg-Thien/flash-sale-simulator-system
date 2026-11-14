# Flash Sale Emulator System — Concurrency Demo

Demo platform để minh họa Race Condition và các Locking Strategies trong hệ thống e-commerce flash sale.

## Tech Stack
| Technologies |
|---|
| **Java 21** + **Spring Boot 3.5**
| **PostgreSQL 17.9** — persistent storage, pessimistic locking (`SELECT ... FOR UPDATE`)
| **Redis 7** + **Redisson** — distributed locking
| **Flyway** — database migration & version control
| **Testcontainers** — integration tests với real containers

## Quick Start

### Prerequisites
- Docker Desktop
- (Optional) JDK 21 + Maven 3.9 để chạy tests

### Chạy bằng Docker Compose

```bash
# 1. Clone repo
git clone <repo-url>
cd flash-sale-platform

# 2. Copy env file
cp .env.example .env
# (Optional) sửa password trong .env

# 3. Khởi động tất cả services
docker compose up -d

# 4. Kiểm tra health
docker compose ps

# 5. Mở Swagger UI
http://localhost:8080/swagger-ui.html
```

### Chạy Demo Simulation

```bash
# Bước 1: Tạo product
curl -X POST http://localhost:8080/api/v1/products \
  -H "Content-Type: application/json" \
  -d '{"name": "iPhone Flash Sale", "price": 15000000, "initialStock": 1}'

# Bước 2: Chạy simulation NONE (tái hiện oversell bug)
curl -X POST http://localhost:8080/api/v1/simulations \
  -H "Content-Type: application/json" \
  -d '{"productId": 1, "initialStock": 1, "concurrentUsers": 20, "lockMode": "NONE"}'

# Bước 3: Chạy simulation PESSIMISTIC (ngăn oversell)
curl -X POST http://localhost:8080/api/v1/simulations \
  -H "Content-Type: application/json" \
  -d '{"productId": 1, "initialStock": 1, "concurrentUsers": 20, "lockMode": "PESSIMISTIC"}'

# Bước 4: So sánh kết quả
# oversellingDetected: true (NONE) vs false (PESSIMISTIC/OPTIMISTIC/REDIS/ATOMIC)
```

## Kiến trúc Locking Strategies

| Strategy | Cơ chế | Thread-safe multi-instance | Best for |
|---|---|---|---|
| `NONE` | Không lock | No | Reproduce bug |
| `OPTIMISTIC` | `@Version` (Hibernate) |  DB | Low contention |
| `PESSIMISTIC` | `SELECT FOR UPDATE` |  DB | High contention |
| `REDIS` | Redisson Distributed Lock |  Redis | Multi-instance |
| `ATOMIC` | Conditional SQL UPDATE, tăng version | DB | Trừ kho có điều kiện trong transaction |

## Chạy Tests

```bash
# Unit tests (không cần Docker)
mvn test -Dtest=LockStrategyTest

# Integration tests (cần Docker Desktop)
mvn test -Dtest=SimulationServiceTest,SimulationComparisonTest,ConcurrencyIntegrationTest,OrderServiceTest

# Tất cả tests
mvn test
```

## Load Testing với K6

Hệ thống cung cấp trọn bộ k6 load test scripts đo throughput, latency và tính toàn vẹn dữ liệu từ bên ngoài qua HTTP.

### Chạy Load Tests

```bash
# 1. Smoke test (kiểm tra nhanh trước khi tải cao)
k6 run load-test/smoke-test.js

# 2. Test từng chiến lược khóa
k6 run load-test/purchase-none.js          # Tái hiện Race condition (NONE)
k6 run load-test/purchase-optimistic.js    # Optimistic locking (@Version)
k6 run load-test/purchase-pessimistic.js   # Pessimistic locking (SELECT FOR UPDATE)
k6 run load-test/purchase-redis.js         # Redisson Distributed Lock
k6 run load-test/purchase-atomic.js        # Conditional SQL UPDATE

# 3. So sánh 5 mode side-by-side
k6 run load-test/comparison.js

# Hoặc chạy toàn bộ test suite bằng script tự động:
# Linux / macOS:
bash load-test/run-all.sh

# Windows (PowerShell):
.\load-test\run-all.ps1
```

### Kết quả K6 Benchmark thực tế (20 Concurrent VUs):

Raw metrics, số đơn commit và stock cuối được lưu trong
[`atomic-lock-comparison`](load-test/results/atomic-lock-comparison/VERIFICATION.md).
Thông số máy và lệnh tái chạy nằm trong
[`ENVIRONMENT.md`](load-test/results/atomic-lock-comparison/ENVIRONMENT.md).

Workload chỉ có 5 đơn vị stock mỗi mode, nên phần lớn request đo phản hồi hết hàng.
Stock không âm chưa đủ chứng minh không oversell; cần đối chiếu số đơn commit với stock ban đầu.
NONE có thể không tái hiện oversell trong lượt k6 ramp-up này; các test simulation dùng người mua bắt đầu đồng thời để kiểm tra lost update.
### Multi-Instance Distributed Lock Verification:
- Hướng dẫn chi tiết chạy 2 instance (Port 8080 & 8081) để chứng minh Redis Lock hoạt động xuyên JVM:  
**[Multi-Instance Testing Guide](multi-instance.md)**

## Dừng services

```bash
docker compose down           # dừng container, giữ data
docker compose down -v        # dừng container, xóa volumes (reset sạch)
```
