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
# oversellingDetected: true (NONE) vs false (PESSIMISTIC/OPTIMISTIC/REDIS)
```

## Kiến trúc Locking Strategies

| Strategy | Cơ chế | Thread-safe multi-instance | Best for |
|---|---|---|---|
| `NONE` | Không lock | No | Reproduce bug |
| `OPTIMISTIC` | `@Version` (Hibernate) |  DB | Low contention |
| `PESSIMISTIC` | `SELECT FOR UPDATE` |  DB | High contention |
| `REDIS` | Redisson Distributed Lock |  Redis | Multi-instance |

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

# 3. So sánh 4 mode side-by-side
k6 run load-test/comparison.js

# Hoặc chạy toàn bộ test suite bằng script tự động:
# Linux / macOS:
bash load-test/run-all.sh

# Windows (PowerShell):
.\load-test\run-all.ps1
```

### Kết quả K6 Benchmark thực tế (20 Concurrent VUs):

```text
═══════════════════════════════════════════════════════════════
     FLASH SALE — K6 LOAD TEST COMPARISON RESULTS              
     Config: 5 items, 20 concurrent users each
═══════════════════════════════════════════════════════════════
Mode            FinalStock   Oversell?    Notes
─────────────────────────────────────────────────────────────
NONE            0             YES       (oversell expected — bug demo)
OPTIMISTIC      0             NO        (lock prevents oversell)
PESSIMISTIC     0             NO        (lock prevents oversell)
REDIS           0             NO        (lock prevents oversell)
═══════════════════════════════════════════════════════════════
```

## Dừng services

```bash
docker compose down           # dừng container, giữ data
docker compose down -v        # dừng container, xóa volumes (reset sạch)
```