package com.hthien.inventory.repository;

import java.util.Optional;

import com.hthien.inventory.model.Reservation;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ReservationRepository {
    private final JdbcClient jdbc;

    ReservationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public boolean insert(long orderId, long productId, int quantity, Reservation.Status status) {
        return jdbc.sql("""
            INSERT INTO stock_reservations (order_id, product_id, quantity, status)
            VALUES (?, ?, ?, ?) ON CONFLICT (order_id) DO NOTHING
            """).params(orderId, productId, quantity, status.name()).update() == 1;
    }

    public Optional<Reservation> find(long orderId) {
        return read("""
            SELECT order_id, product_id, quantity, status FROM stock_reservations WHERE order_id = ?
            """, orderId);
    }

    public Reservation lock(long orderId) {
        return read("""
            SELECT order_id, product_id, quantity, status FROM stock_reservations WHERE order_id = ? FOR UPDATE
            """, orderId).orElseThrow();
    }

    private Optional<Reservation> read(String sql, long orderId) {
        return jdbc.sql(sql).param(orderId)
            .query((row, index) -> new Reservation(row.getLong("order_id"), row.getLong("product_id"),
                row.getInt("quantity"), Reservation.Status.valueOf(row.getString("status"))))
            .optional();
    }

    public void updateStatus(long orderId, Reservation.Status status) {
        jdbc.sql("UPDATE stock_reservations SET status = ?, updated_at = NOW() WHERE order_id = ?")
            .params(status.name(), orderId).update();
    }

    public long reservedQuantity(long productId) {
        return jdbc.sql("""
            SELECT COALESCE(SUM(quantity), 0) FROM stock_reservations WHERE product_id = ? AND status = 'RESERVED'
            """).param(productId).query(Long.class).single();
    }
}
