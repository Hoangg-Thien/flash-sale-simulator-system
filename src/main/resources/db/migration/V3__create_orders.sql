-- V3: Tạo bảng orders
-- idempotency_key: UNIQUE cho chống duplicate purchase
-- lock_mode: lưu strategy nào đã được dùng (NONE/OPTIMISTIC/PESSIMISTIC/REDIS)
CREATE TABLE IF NOT EXISTS orders (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    product_id      BIGINT       NOT NULL,
    order_status    VARCHAR(20)  NOT NULL CHECK (order_status IN ('SUCCESS', 'FAILED', 'PENDING')),
    idempotency_key VARCHAR(64)  NULL,
    lock_mode       VARCHAR(20)  NOT NULL CHECK (lock_mode IN ('NONE', 'OPTIMISTIC', 'PESSIMISTIC', 'REDIS')),
    requested_at    TIMESTAMPTZ  NOT NULL,
    completed_at    TIMESTAMPTZ  NULL,
    latency_ms      BIGINT       NULL,
    error_message   VARCHAR(255) NULL,

    CONSTRAINT fk_orders_product
        FOREIGN KEY (product_id) REFERENCES products (id) ON DELETE RESTRICT
);

-- Unique index trên idempotency_key (nullable — không phải tất cả request đều có)
-- Partial index: chỉ index các row có idempotency_key NOT NULL
CREATE UNIQUE INDEX IF NOT EXISTS idx_orders_idempotency_key
    ON orders (idempotency_key)
    WHERE idempotency_key IS NOT NULL;

COMMENT ON COLUMN orders.idempotency_key IS 'Client-provided key to prevent duplicate purchases';
COMMENT ON COLUMN orders.lock_mode IS 'Lock strategy used for this order — for analysis and comparison';
