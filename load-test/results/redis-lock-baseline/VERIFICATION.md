# Phase 0 verification

Starting SHA: `02f2d99d1cf0a64a1fa8083a488fe215549ff025`. Work is on `feature/hardening`; changes are intentionally uncommitted and unpushed at the user's request. Phase 1 has not started.

## Changes

- `InventoryLockStrategy`: default `around(productId, Supplier<T>)` passes through for database strategies.
- `RedisLockStrategy`: moved the existing lock acquisition/release into `around`, retaining the key, wait timeout, watchdog-enabled overload, exception mapping and ownership guard. `deductStock` keeps the existing stock check/update.
- `OrderService`: wraps the existing transaction in `strategy.around(...)`; order creation and error handling are unchanged.
- `LockStrategyUnitTest`: existing Redis tests call the wrapper around deduction; assertions retained.
- `RedisLockTransactionIntegrationTest`: records real Redis unlock and Spring transaction callbacks against Testcontainers PostgreSQL/Redis. Covers success, failure after deduction and an injected `beforeCommit` failure. Asserts acquisition occurs outside a transaction, deduction occurs inside one, completion precedes unlock, persisted stock/order counts, and lock release.
- This directory contains test evidence, raw k6 output, environment details and repeat commands. The existing root Compose modification, migration plan, unrelated documentation and PDF were preserved.

## Test evidence

1. **RED, before production edits:** the new three-case integration test compiled and ran, with 3 assertion failures and 0 errors. Actual order was `LOCKED, UNLOCKED, COMMITTED/ROLLED_BACK`. See [ordering-red.txt](ordering-red.txt). An initial missing test import was corrected before this genuine RED run.
2. **GREEN:** `./mvnw.cmd test`: 39 discovered, 38 passed, 1 skipped, 0 failures/errors.
3. **Stability:** Case 6b passed **30/30**; Redis STABILITY passed **30/30**, with its existing 3 simulations per invocation (90 simulations). Total **120 simulations**. See [stability.txt](stability.txt). [run-stability.ps1](run-stability.ps1) temporarily replaces only the two selected `@Test` annotations with `@RepeatedTest(30)` and restores the original bytes in `finally`. Both original test files were verified unchanged afterward.
4. **Final clean build:** `./mvnw.cmd clean package`: 39 discovered, **38 passed**, **1 skipped**, 0 failures/errors; BUILD SUCCESS. See [suite.txt](suite.txt). The skipped `FlashSaleApplicationTests.contextLoads` already had `@Disabled`; this change adds no skips. Existing deprecated `@SpyBean` and Mockito/JDK agent warnings remain.
5. Independent read-only review found no Critical/Important issues in the production fix or regression tests.

Run the stability script from the repository root before a clean suite run; it leaves the original test sources intact. Detailed local execution logs are `phase0-red.log`, `phase0-stability.log` and `phase0-final-suite.log` (ignored log files).

## Plan reconciliation and boundaries

- The migration plan is actually under `docs/`; its described Redis bug matches the current code.
- The snapshot's statement that no baseline files exist is stale locally: September raw results exist but are ignored by Git. They were left untouched; this directory records a new post-fix baseline.
- The plan's "three Redis STABILITY tests" is one test with three internal iterations. All three iterations were repeated 30 times.
- Raw JSON is saved losslessly as gzip-compressed JSON Lines to keep the evidence manageable. The nested directory is visible to Git without changing `.gitignore`.
- The plan asks to commit the evidence. That step is intentionally deferred to the user, following the explicit no-commit/no-push instruction.

## k6 baseline

One full warm-up followed by three measured runs of the unchanged comparison script. All three measured runs exited **0**, passed the `<1%` HTTP error threshold, and had zero failed checks. Summary statistics include all modes and setup/teardown requests; they do not rank individual lock strategies.

| Run | HTTP requests | HTTP RPS | p50 ms | p95 ms | p99 ms | Built-in HTTP error % |
|---|---:|---:|---:|---:|---:|---:|
| 1 | 68321 | 711.07 | 7.83 | 77.21 | 106.09 | 0.0117 |
| 2 | 91852 | 956.10 | 5.67 | 66.69 | 82.86 | 0.0087 |
| 3 | 91313 | 950.52 | 6.00 | 66.53 | 88.98 | 0.0088 |

Each run committed **20 successful orders** total (5 per mode); every product finished at stock **0**. Independent PostgreSQL counts in `comparison-N-stock.csv` satisfy `confirmed + remaining = 5` for every safe mode. The eight built-in HTTP failures per run are the existing response callback classifying successful reset/teardown HTTP 200 responses as unexpected; purchase checks all passed. The RPS variation is reported rather than selecting the best run.

Raw outputs: [run 1](comparison-1.json.gz), [run 2](comparison-2.json.gz), [run 3](comparison-3.json.gz). Each gzip file was read to EOF successfully after the runs. Matching `*-summary.json` files contain the k6 summary, `comparison-N.txt` contains console output, and [run-status.txt](run-status.txt) records exit codes. [ENVIRONMENT.md](ENVIRONMENT.md) records the exact environment and reproduction commands. The dedicated benchmark containers/network/volumes were removed after collection.

NONE did not oversell in these k6 runs: the gradual ramp and stock of only 5 can sell out before contention builds. This workload does not establish NONE correctness; the existing simultaneous-start simulation tests still exercise its deliberate lost-update behavior.

All Phase 0 implementation and verification tasks are complete. Committing the files is intentionally left to the user; no Phase 1 changes were made.

## Remaining limits

- The boundary guarantee assumes the callback finishes the owning transaction. Current callers have no ambient transaction; a future caller with an outer REQUIRED transaction would need separate design/testing before relying on this wrapper.
- The injected commit failure happens in `beforeCommit`, not during a physical database/network commit. Existing rollback and Redis-unavailable integration tests also pass; post-commit Redis failure/reply uncertainty remains outside this fix.
- The two-second lock wait can still time out under sufficient contention; the fix does not add retries or change the public API.
- The k6 workload limitations and exact runtime/configuration are recorded in [ENVIRONMENT.md](ENVIRONMENT.md). No multi-instance proof, ATOMIC mode, service extraction or Phase 5 benchmark matrix is included.
