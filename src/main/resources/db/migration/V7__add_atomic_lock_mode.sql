ALTER TABLE orders DROP CONSTRAINT orders_lock_mode_check;
ALTER TABLE orders ADD CONSTRAINT orders_lock_mode_check
    CHECK (lock_mode IN ('NONE', 'OPTIMISTIC', 'PESSIMISTIC', 'REDIS', 'ATOMIC'));

ALTER TABLE simulation_runs DROP CONSTRAINT simulation_runs_lock_mode_check;
ALTER TABLE simulation_runs ADD CONSTRAINT simulation_runs_lock_mode_check
    CHECK (lock_mode IN ('NONE', 'OPTIMISTIC', 'PESSIMISTIC', 'REDIS', 'ATOMIC'));
