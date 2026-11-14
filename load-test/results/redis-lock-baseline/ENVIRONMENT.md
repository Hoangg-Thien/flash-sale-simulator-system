# Phase 0 baseline — 2026-11-14 (Asia/Bangkok)

- Starting commit: `02f2d99d1cf0a64a1fa8083a488fe215549ff025`; branch `feature/hardening`, with the uncommitted Phase 0 fix. No commit or push performed.
- Host: Windows 11 x64, AMD Ryzen 5 7535HS, 6 cores / 12 logical CPUs, 15.32 GiB RAM. Docker Desktop Linux engine 29.6.2: 12 CPUs, 7,968,628,736 bytes RAM available. VS Code and other desktop processes remained running; laptop measurements are not production capacity estimates.
- Build/tests: Oracle JDK 21.0.2, Maven wrapper 3.9.16. Application runtime: Temurin 21.0.13+9, `-XX:MaxRAMPercentage=75.0`.
- PostgreSQL 17.9; Redis 7.4.11; k6 2.2.0 (`00a9a1b7f5`, Go 1.26.5, native Windows amd64). Integration tests use the existing PostgreSQL 16 / Redis 7 Testcontainers configuration.
- Single application instance at `http://127.0.0.1:18080`. Dedicated Compose project `flashsale-phase0`, no local database or existing Compose volumes used. See [compose.yml](compose.yml).
- Container limits: app 2 CPUs / 1 GiB; PostgreSQL 2 CPUs / 1 GiB; Redis 1 CPU / 256 MiB. k6 runs on the host without a CPU/memory cap. SQL logging disabled and root/Hibernate SQL logging at WARN for all benchmark runs.
- Script: unchanged `load-test/comparison.js`, one product per mode, initial stock **5**, quantity **1**, peak **20 VUs**. Each mode ramps up 3s, holds 15s, ramps down 3s; starts at 0/25/50/75s, total nominal 96s. Modes: NONE, OPTIMISTIC, PESSIMISTIC, REDIS.
- One full comparison warm-up, then three measured comparisons, sequentially. Benchmark tables truncated and identities reset, dedicated Redis DB flushed between measured runs. JVM stays running. No integration tests run concurrently with the measurements.
- Raw event output is gzip-compressed JSON Lines (`comparison-N.json.gz`), with k6 summary JSON, console output and independent database stock/order counts for every run. Nested results are visible to Git without changing the existing ignore policy. Historical ignored results from September remain untouched.
- Tested JAR SHA-256: `8B29195AF781256A281358B942C7CBC31A0909457948D2934DB6B2063890EDD1`.

Image digests used:

```text
eclipse-temurin@sha256:ac05c3c42b7c2f1a52c9bd52f01041fa1958231b15ebeae7420429cbc5baf474
postgres@sha256:b994732fcf33f73776c65d3a5bf1f80c00120ba5007e8ab90307b1a743c1fc16
redis@sha256:ff02b58f971e7d7d156a1267e283fcbbeee91773b6aa36c49dac28ecfe28eadf
```

## Reproduce

From the repository root, with Docker Desktop and k6 running/installed:

```powershell
.\mvnw.cmd clean package
docker compose -p flashsale-phase0 -f load-test/results/redis-lock-baseline/compose.yml up -d
# Wait until GET http://127.0.0.1:18080/actuator/health returns UP.
k6 run --quiet --env BASE_URL=http://127.0.0.1:18080 load-test/comparison.js
powershell -NoProfile -File load-test/results/redis-lock-baseline/run-baseline.ps1
docker compose -p flashsale-phase0 -f load-test/results/redis-lock-baseline/compose.yml down -v
```

Archive existing Phase 0 output before reproducing: the scripts overwrite run files; `run-status.txt` appends statuses. Pin the image digests above to reproduce these exact runtime versions.

## Interpretation limits

The original comparison script determines overselling only from negative stock. Lost updates in NONE can produce too many orders while stock stays nonnegative. The saved `*-stock.csv` files independently count committed successful orders; safe modes must satisfy `confirmed + final_stock = 5` and `final_stock >= 0`.

Five units sell out near the beginning of each mode, so most requests measure rejection/sold-out handling. OPTIMISTIC conflicts can reject purchases while stock is still available; sustained retries in this workload can hide that effect. This is the requested existing-script baseline, not the Phase 5 benchmark matrix or proof of multi-instance correctness. The script's response callback also classifies setup/reset/teardown HTTP 200 responses as unexpected; those few requests appear in the built-in error rate even when purchase responses are correct.
