# ADR-001: Conditional SQL UPDATE alongside optimistic, pessimistic and Redis locking

- Status: Accepted
- Date: 2026-11-14
- Scope: the existing single-service application; migration plan v2, Phase 1

## Context

Purchasing must deduct inventory and create an order in the same database transaction. Existing modes demonstrate lost updates (NONE), optimistic conflict detection, pessimistic row locking and Redis serialization. The Phase 0 fix makes the Redis lock wrap the whole order transaction; its ordering regression tests remain unchanged.

We need a database-only comparison that avoids a separate stock read/check before attempting a deduction. The application uses PostgreSQL, Spring Data JPA 3.5 and an `Inventory` entity with `@Version` and an application-clock `@PreUpdate` timestamp mapped to `update_at`.

## Decision

Add ATOMIC as an additional strategy; keep all existing modes and API shapes. Its native SQL is:

```sql
UPDATE inventory
SET stock = stock - :quantity,
    version = version + 1,
    update_at = :updatedAt
WHERE product_id = :productId AND stock >= :quantity;
```

`updatedAt` comes from `Instant.now()`, preserving the application-clock behavior of the entity callback that bulk SQL bypasses. Successful deductions increment the version once, allowing stale optimistic writers to be rejected. The existing unique product constraint limits the result to zero or one affected row.

Under PostgreSQL's default READ COMMITTED isolation, a competing updater waits and re-evaluates its WHERE condition against the committed row. The availability check and subtraction therefore happen in one write statement. This is not lock-free: PostgreSQL still takes a row lock until the order transaction completes.

The query uses `@Modifying(flushAutomatically = true, clearAutomatically = true)`. Flushing preserves pending managed changes; clearing prevents a previously loaded Inventory from masking the result of the bulk update. A fresh SELECT returns Inventory to honor the existing strategy interface. Thus the deduction is one conditional write, while the complete strategy currently performs one write plus one read, in addition to the existing product lookup and order persistence.

If no row was updated, the inventory read distinguishes unavailable/missing inventory (`ProductNotFoundException`, matching existing strategies) from insufficient stock (`InsufficientStockException`). No additional Product existence query is added on the success path. Public quantity validation remains 1–100; strategies continue to assume validated quantities, as before.

Migration V7 extends the CHECK constraints on `orders.lock_mode` and `simulation_runs.lock_mode` to include ATOMIC. Earlier Flyway migrations are immutable. This migration validates existing data and acquires PostgreSQL table locks; it does not promise a zero-downtime rollout on large tables.

## Alternatives and trade-offs

| Mode | Deduction behavior | Contention/failure behavior | Cost and dependencies |
|---|---|---|---|
| ATOMIC | Conditional write, explicit version/timestamp update; cleared-context readback | Waits on the database row; zero affected rows means insufficient/missing inventory | No Redis operation in the purchase path; bulk update clears the transaction's managed context |
| OPTIMISTIC | Read entity, change stock, version-checked update | Concurrent stale writers fail; rejected conflicts are not retried, so available stock can remain unsold | Database only; fewer explicit lock concerns but conflict handling is required |
| PESSIMISTIC | SELECT FOR UPDATE, check and update entity | Competing transactions wait until the current transaction completes | Database only; holds a row lock/connection, subject to database timeouts/deadlocks |
| REDIS | Redis lock wraps the entire transaction; entity updates remain version checked | Serializes same-product purchases; two-second acquisition wait may fail; outage can block this mode | Redis availability/watchdog and an owning transaction boundary are required |

Keep all four safe modes for comparison, not because one is universally fastest. NONE remains an intentional unsafe demonstration; mixing it into a workload invalidates consistency guarantees. ATOMIC has no explicit retry loop and remains subject to database failures, lock waits and transaction rollback. Redis still initializes at application startup; ATOMIC's working during a later Redis outage is not proof that the application can start without Redis.

## Verification and limits

Integration tests cover sufficient/insufficient/zero stock, multi-unit purchases, simultaneous buyers, version/timestamp updates, stale optimistic writes, pending changes and fresh persistence-context reads, order failure rollback, idempotency and price snapshots. A V6-to-V7 upgrade test checks existing data and the two CHECK constraints. Simulation comparison, standalone k6 and manual GitHub Actions support all five modes.

Benchmark evidence is stored separately in `load-test/results/atomic-lock-comparison/`. The comparison workload has only five units per mode and predominantly measures sold-out responses; it does not establish successful-order capacity or multi-instance behavior. No speed ranking is asserted in this ADR. Phase 2 and service extraction are deferred.

## Sources

- [PostgreSQL 17: Read Committed isolation](https://www.postgresql.org/docs/17/transaction-iso.html#XACT-READ-COMMITTED): concurrent UPDATE waits and re-evaluates its predicate.
- [Spring Data JPA 3.5: Modifying queries](https://docs.spring.io/spring-data/jpa/reference/3.5/jpa/query-methods.html#jpa.modifying-queries): bulk updates and persistence-context freshness.
- [Spring Data JPA 3.5: Modifying annotation](https://docs.spring.io/spring-data/jpa/docs/3.5.x/api/org/springframework/data/jpa/repository/Modifying.html): flush-before and clear-after options.
