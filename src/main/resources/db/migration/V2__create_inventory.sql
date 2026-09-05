-- V2: Tạo bảng inventory
-- version dùng cho Optimistic Lock (@Version trong Hibernate)
CREATE TABLE IF NOT EXISTS inventory (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    product_id BIGINT  NOT NULL,
    stock      INTEGER NOT NULL CHECK (stock >= 0),
    version    BIGINT  NOT NULL DEFAULT 0,
    update_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_inventory_product_id UNIQUE (product_id),
    CONSTRAINT fk_inventory_product
        FOREIGN KEY (product_id) REFERENCES products (id) ON DELETE CASCADE
);

COMMENT ON COLUMN inventory.version IS 'Optimistic lock version — incremented by Hibernate on each UPDATE';
COMMENT ON COLUMN inventory.stock IS 'Current available stock, protected by locking strategies';
