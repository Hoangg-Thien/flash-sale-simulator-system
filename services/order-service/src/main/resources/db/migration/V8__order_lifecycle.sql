-- The legacy purchase transaction never committed a pending order.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM orders WHERE order_status = 'PENDING') THEN
        RAISE EXCEPTION 'Resolve legacy PENDING orders before enabling distributed reconciliation';
    END IF;
END $$;
ALTER TABLE orders DROP CONSTRAINT orders_order_status_check;
UPDATE orders SET order_status = 'CONFIRMED' WHERE order_status = 'SUCCESS';
UPDATE orders SET order_status = 'REJECTED' WHERE order_status = 'FAILED';
ALTER TABLE orders ADD CONSTRAINT orders_order_status_check
    CHECK (order_status IN ('PENDING','CONFIRMED','REJECTED'));
ALTER TABLE orders ADD COLUMN cancel_requested BOOLEAN NOT NULL DEFAULT false,
                   ADD COLUMN next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now();
CREATE INDEX orders_reconcile_idx ON orders (next_attempt_at, id) WHERE order_status = 'PENDING';
