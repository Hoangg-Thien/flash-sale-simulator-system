-- V6: Tạo bảng simulation_requests
-- Lưu chi tiết từng thread trong 1 simulation run
CREATE TABLE IF NOT EXISTS simulation_requests (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    simulation_run_id BIGINT      NOT NULL,
    thread_index      INTEGER     NOT NULL,
    status            VARCHAR(20) NOT NULL CHECK (status IN ('SUCCESS', 'FAILED', 'PENDING')),
    latency_ms        BIGINT      NULL,
    http_status       INTEGER     NULL,
    error_message     VARCHAR(255) NULL,
    order_id          BIGINT      NULL,

    CONSTRAINT fk_sim_requests_run
        FOREIGN KEY (simulation_run_id) REFERENCES simulation_runs (id) ON DELETE CASCADE,
    CONSTRAINT fk_sim_requests_order
        FOREIGN KEY (order_id) REFERENCES orders (id) ON DELETE SET NULL
);

CREATE INDEX IF NOT EXISTS idx_sim_requests_run_id ON simulation_requests (simulation_run_id);

COMMENT ON COLUMN simulation_requests.thread_index IS '0-indexed thread position in the concurrent batch';
COMMENT ON COLUMN simulation_requests.order_id IS 'NULL if request failed; references the created order if success';
