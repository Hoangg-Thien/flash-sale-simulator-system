-- Quiesce legacy writers before upgrading. Each seed is a durable one-time
-- provisioning command, cleared only after Inventory acknowledges initialization.
ALTER TABLE products ADD COLUMN pending_initial_stock INTEGER CHECK (pending_initial_stock >= 0);
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM products p LEFT JOIN inventory i ON i.product_id = p.id WHERE i.id IS NULL) THEN
        RAISE EXCEPTION 'Missing legacy inventory: restore stock snapshot before cutover';
    END IF;
END $$;
UPDATE products p SET pending_initial_stock = i.stock FROM inventory i WHERE i.product_id = p.id;
DROP TABLE inventory;
CREATE INDEX products_pending_initialization ON products (id) WHERE pending_initial_stock IS NOT NULL;

ALTER TABLE simulation_requests DROP CONSTRAINT simulation_requests_status_check;
UPDATE simulation_requests SET status = 'CONFIRMED' WHERE status = 'SUCCESS';
UPDATE simulation_requests SET status = 'REJECTED' WHERE status = 'FAILED';
ALTER TABLE simulation_requests ADD CHECK (status IN ('CONFIRMED','REJECTED','PENDING','ERROR'));
