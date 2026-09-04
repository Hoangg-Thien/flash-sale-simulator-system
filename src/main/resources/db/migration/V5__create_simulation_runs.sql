-- V5: Tạo bảng simulation_runs
-- Lưu config và kết quả tổng hợp mỗi lần chạy concurrent simulation
CREATE TABLE IF NOT EXISTS simulation_runs (
    id                   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    product_id           BIGINT      NOT NULL,
    initial_stock        INTEGER     NOT NULL,
    concurrent_users     INTEGER     NOT NULL,
    lock_mode            VARCHAR(20) NOT NULL CHECK (lock_mode IN ('NONE', 'OPTIMISTIC', 'PESSIMISTIC', 'REDIS')),
    started_at           TIMESTAMPTZ NOT NULL,
    finished_at          TIMESTAMPTZ NULL,
    final_stock          INTEGER     NULL,
    success_count        INTEGER     NULL,
    failed_count         INTEGER     NULL,
    overselling_detected BOOLEAN     NOT NULL DEFAULT FALSE,
    throughput_rps       NUMERIC(10, 2) NULL,
    avg_latency_ms       NUMERIC(10, 2) NULL,
    p95_latency_ms       NUMERIC(10, 2) NULL,

    CONSTRAINT fk_simulation_runs_product
        FOREIGN KEY (product_id) REFERENCES products (id) ON DELETE RESTRICT
);

COMMENT ON TABLE simulation_runs IS 'Aggregate results for each concurrent purchase simulation run';
COMMENT ON COLUMN simulation_runs.overselling_detected IS 'TRUE if final_stock < 0 OR success_count > initial_stock';
