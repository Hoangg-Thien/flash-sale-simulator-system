-- Rejections and cancellation tombstones can precede stock creation.
CREATE TABLE stock_reservations (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    order_id   BIGINT NOT NULL UNIQUE CHECK (order_id > 0),
    product_id BIGINT NOT NULL CHECK (product_id > 0),
    quantity   INTEGER NOT NULL CHECK (quantity > 0),
    status     VARCHAR(20) NOT NULL CHECK (status IN ('RESERVED', 'REJECTED', 'RELEASED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX stock_reservations_active_product_idx
    ON stock_reservations (product_id) WHERE status = 'RESERVED';
