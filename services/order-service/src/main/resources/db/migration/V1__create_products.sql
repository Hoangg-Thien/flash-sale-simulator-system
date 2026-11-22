-- V1: Tạo bảng products
CREATE TABLE IF NOT EXISTS products (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name       VARCHAR(255)   NOT NULL,
    price      NUMERIC(12, 2) NOT NULL CHECK (price >= 0),
    created_at TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    update_at  TIMESTAMPTZ    NOT NULL DEFAULT NOW()
);

COMMENT ON TABLE products IS 'Product catalog for flash sale';
COMMENT ON COLUMN products.price IS 'Price at time of creation, snapshot in order_items';
