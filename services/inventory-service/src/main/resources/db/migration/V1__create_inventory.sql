CREATE TABLE inventory (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    product_id BIGINT NOT NULL UNIQUE CHECK (product_id > 0),
    stock      INTEGER NOT NULL CHECK (stock >= 0),
    version    BIGINT NOT NULL DEFAULT 0,
    update_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
