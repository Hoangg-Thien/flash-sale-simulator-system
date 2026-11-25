ALTER TABLE inventory ADD COLUMN initial_stock INTEGER CHECK (initial_stock >= 0);
