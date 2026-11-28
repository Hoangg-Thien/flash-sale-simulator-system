package com.hthien.flash_sale.integration;

import static org.assertj.core.api.Assertions.*;

import java.sql.DriverManager;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class AtomicMigrationIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Test
    void upgradeFromV6_preservesExistingDataAndAllowsAtomicOrdersAndSimulations() throws Exception {
        Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
            .target("6").load().migrate();
        try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO products (name, price) VALUES ('Migration fixture', 10)");
            statement.executeUpdate("INSERT INTO inventory (product_id, stock, version) VALUES (1, 10, 4)");
            statement.executeUpdate("""
                INSERT INTO orders (product_id, order_status, lock_mode, requested_at)
                VALUES (1, 'SUCCESS', 'REDIS', now())
                """);
            Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .target("7").load().migrate();
            statement.executeUpdate("""
                INSERT INTO orders (product_id, order_status, lock_mode, requested_at)
                VALUES (1, 'SUCCESS', 'ATOMIC', now())
                """);
            statement.executeUpdate("""
                INSERT INTO simulation_runs (product_id, initial_stock, concurrent_users, lock_mode, started_at)
                VALUES (1, 10, 20, 'ATOMIC', now())
                """);
            try (var rows = statement.executeQuery("SELECT stock, version FROM inventory WHERE product_id = 1")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getInt("stock")).isEqualTo(10);
                assertThat(rows.getLong("version")).isEqualTo(4);
            }
            try (var rows = statement.executeQuery("SELECT count(*) FROM orders")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getInt(1)).isEqualTo(2);
            }
            assertThatThrownBy(() -> statement.executeUpdate("""
                INSERT INTO orders (product_id, order_status, lock_mode, requested_at)
                VALUES (1, 'SUCCESS', 'INVALID', now())
                """))
                .isInstanceOf(java.sql.SQLException.class)
                .extracting(error -> ((java.sql.SQLException) error).getSQLState()).isEqualTo("23514");
            assertThatThrownBy(() -> statement.executeUpdate("""
                INSERT INTO simulation_runs (product_id, initial_stock, concurrent_users, lock_mode, started_at)
                VALUES (1, 10, 20, 'INVALID', now())
                """))
                .isInstanceOf(java.sql.SQLException.class)
                .extracting(error -> ((java.sql.SQLException) error).getSQLState()).isEqualTo("23514");

            statement.executeUpdate("INSERT INTO orders(product_id,order_status,lock_mode,requested_at) VALUES(1,'PENDING','ATOMIC',now())");
            assertThatThrownBy(() -> Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()).load().migrate())
                .hasStackTraceContaining("Resolve legacy PENDING orders");
            statement.executeUpdate("DELETE FROM orders WHERE order_status='PENDING'");
            Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .load().migrate();
            try (var rows = statement.executeQuery("""
                SELECT pending_initial_stock, to_regclass('inventory') IS NULL AS stock_moved,
                       (SELECT count(*) FROM orders WHERE order_status='CONFIRMED') AS confirmed
                FROM products WHERE id=1
                """)) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getInt("pending_initial_stock")).isEqualTo(10);
                assertThat(rows.getBoolean("stock_moved")).isTrue();
                assertThat(rows.getInt("confirmed")).isEqualTo(2);
            }
        }
    }
}
