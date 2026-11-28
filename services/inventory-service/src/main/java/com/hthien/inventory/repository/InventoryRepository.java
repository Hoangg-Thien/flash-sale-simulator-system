package com.hthien.inventory.repository;

import java.util.Optional;

import com.hthien.inventory.model.Inventory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class InventoryRepository {
    private final JdbcClient jdbc;

    InventoryRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Inventory> find(long productId) {
        return read("SELECT product_id, stock, version FROM inventory WHERE product_id = ?", productId);
    }

    public Optional<Inventory> lock(long productId) {
        return read("SELECT product_id, stock, version FROM inventory WHERE product_id = ? FOR UPDATE", productId);
    }

    private Optional<Inventory> read(String sql, long productId) {
        return jdbc.sql(sql).param(productId)
            .query((row, index) -> new Inventory(row.getLong("product_id"), row.getInt("stock"), row.getLong("version")))
            .optional();
    }

    public boolean deductAtomically(long productId, int quantity) {
        return jdbc.sql("""
            UPDATE inventory SET stock = stock - ?, version = version + 1, update_at = NOW()
            WHERE product_id = ? AND stock >= ?
            """).params(quantity, productId, quantity).update() == 1;
    }

    public boolean updateIfVersionMatches(Inventory inventory, int stock) {
        return jdbc.sql("""
            UPDATE inventory SET stock = ?, version = version + 1, update_at = NOW()
            WHERE product_id = ? AND version = ?
            """).params(stock, inventory.productId(), inventory.version()).update() == 1;
    }

    public void updateStock(long productId, int stock) {
        jdbc.sql("UPDATE inventory SET stock = ?, version = version + 1, update_at = NOW() WHERE product_id = ?")
            .params(stock, productId).update();
    }

    public void restore(long productId, int quantity) {
        int updated = jdbc.sql("""
            UPDATE inventory SET stock = stock + ?, version = version + 1, update_at = NOW() WHERE product_id = ?
            """).params(quantity, productId).update();
        if (updated != 1) {
            throw new IllegalStateException("Reserved product has no inventory row");
        }
    }

    public boolean initialize(long productId, int stock) {
        return jdbc.sql("""
            INSERT INTO inventory (product_id, stock, initial_stock) VALUES (?, ?, ?)
            ON CONFLICT (product_id) DO NOTHING
            """).params(productId, stock, stock).update() == 1;
    }

    public boolean matchesInitialStock(long productId, int stock) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM inventory WHERE product_id = ? AND initial_stock = ?)")
            .params(productId, stock).query(Boolean.class).single();
    }

    public boolean createIfAbsent(long productId, int stock) {
        return jdbc.sql("INSERT INTO inventory (product_id, stock) VALUES (?, ?) ON CONFLICT (product_id) DO NOTHING")
            .params(productId, stock).update() == 1;
    }
}
