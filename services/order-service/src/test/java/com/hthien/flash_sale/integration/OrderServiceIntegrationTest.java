package com.hthien.flash_sale.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hthien.flash_sale.FlashSaleApplication;
import com.hthien.flash_sale.service.OrderService;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Real Order HTTP, Flyway and PostgreSQL. The Inventory HTTP fixture controls delivery at precise crash boundaries. */
@Testcontainers
class OrderServiceIntegrationTest {
    @Container static final PostgreSQLContainer<?> db = new PostgreSQLContainer<>("postgres:17.9");
    static final ObjectMapper json = new ObjectMapper();
    static final HttpClient client = HttpClient.newHttpClient();
    static final InventoryPeer peer = new InventoryPeer();
    static ServletWebServerApplicationContext app;
    static HttpServer server;
    static String base;

    @BeforeAll
    static void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/v1/reservations", peer::handle);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        startOrder(3600000);
    }

    static void startOrder(long delay) {
        app = (ServletWebServerApplicationContext) SpringApplication.run(FlashSaleApplication.class,
            "--server.port=0", "--spring.datasource.url=" + db.getJdbcUrl(),
            "--spring.datasource.username=" + db.getUsername(), "--spring.datasource.password=" + db.getPassword(),
            "--inventory.base-urls=http://127.0.0.1:" + server.getAddress().getPort(),
            "--inventory.connect-timeout-ms=500", "--inventory.read-timeout-ms=5000", "--inventory.retries=1",
            "--orders.reconcile-age-ms=1", "--orders.cancel-age-ms=600000", "--orders.reconcile-delay-ms=" + delay,
            "--spring.jpa.show-sql=false", "--logging.level.root=WARN");
        base = "http://127.0.0.1:" + app.getWebServer().getPort() + "/api/v1/orders";
    }

    @AfterAll
    static void close() {
        if (app != null) app.close();
        if (server != null) server.stop(0);
    }

    @BeforeEach
    void reset() throws Exception {
        peer.reset();
        execute("TRUNCATE products RESTART IDENTITY CASCADE");
        execute("INSERT INTO products(name,price) VALUES ('phone',12.34)");
    }

    @Test
    void confirmedAndInsufficientAndReplayedOrdersPreservePriceAndPayload() throws Exception {
        assertThat(purchase("key", 2).statusCode()).isEqualTo(201);
        assertThat(purchase("key", 2).statusCode()).isEqualTo(200);
        assertThat(purchase("key", 3).statusCode()).isEqualTo(409);
        assertThat(status("key")).isEqualTo("CONFIRMED");
        assertThat(query("SELECT unit_price FROM order_items")).isEqualTo("12.34");
        assertThat(purchase("sold-out", 3).statusCode()).isEqualTo(409);
        assertThat(status("sold-out")).isEqualTo("REJECTED");
        assertThat(peer.stock).isEqualTo(1);
        assertThat(query("SELECT count(*) FROM orders")).isEqualTo("2");
    }

    @Test
    void concurrentRetriesCreateOneSnapshotAndOneReservation() throws Exception {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var start = new CountDownLatch(1);
            var calls = new ArrayList<java.util.concurrent.Future<Integer>>();
            for (int i = 0; i < 16; i++) {
                calls.add(executor.submit(() -> {
                    start.await();
                    return purchase("racing-key", 1).statusCode();
                }));
            }
            start.countDown();
            for (var call : calls) assertThat(call.get(15, TimeUnit.SECONDS)).isIn(200, 201, 202);
        }
        assertThat(status("racing-key")).isEqualTo("CONFIRMED");
        assertThat(query("SELECT count(*) FROM orders")).isEqualTo("1");
        assertThat(query("SELECT count(*) FROM order_items")).isEqualTo("1");
        assertThat(peer.stock).isEqualTo(2);
        assertThat(peer.reservations).hasSize(1);
    }

    @Test
    void commitWithLostResponsesRecoversFromPersistedDataAfterRestart() throws Exception {
        peer.dropReserveReply = true;
        assertThat(purchase("lost", 2).statusCode()).isEqualTo(202);
        assertThat(status("lost")).isEqualTo("PENDING");
        assertThat(peer.stock).isEqualTo(1);
        assertThat(peer.reserveCalls.get()).isBetween(1, 2);
        peer.dropReserveReply = false;
        app.close();
        startOrder(100);
        try {
            await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(status("lost")).isEqualTo("CONFIRMED"));
            assertThat(peer.stock).isEqualTo(1);
            assertThat(peer.reservations).hasSize(1);
        } finally {
            app.close();
            startOrder(3600000);
        }
    }

    @Test
    void missingReservationBeforeDeadlineIsReservedAgainWithOriginalId() throws Exception {
        peer.unavailable = true;
        assertThat(purchase("retry", 2).statusCode()).isEqualTo(202);
        peer.unavailable = false;
        reconcile();
        assertThat(status("retry")).isEqualTo("CONFIRMED");
        long id = Long.parseLong(query("SELECT id FROM orders"));
        assertThat(peer.reservations.get(id)).isEqualTo(new Reservation(id, 1, 2, "RESERVED"));
        assertThat(peer.stock).isEqualTo(1);
    }

    @Test
    void readTimeoutAfterReservationCommitDoesNotRejectOrSpendTwice() throws Exception {
        var gate = new CountDownLatch(1);
        peer.replyGate = gate;
        try {
            assertThat(purchase("timeout", 2).statusCode()).isEqualTo(202);
            assertThat(status("timeout")).isEqualTo("PENDING");
            assertThat(peer.reserveCalls.get()).isBetween(1, 2);
            assertThat(peer.stock).isEqualTo(1);
        } finally {
            gate.countDown();
        }
        reconcile();
        assertThat(status("timeout")).isEqualTo("CONFIRMED");
        assertThat(peer.stock).isEqualTo(1);
    }

    @Test
    void missingReservationAndUnconfirmedCancellationStayPendingAcrossRecovery() throws Exception {
        peer.unavailable = true;
        assertThat(purchase("absent", 1).statusCode()).isEqualTo(202);
        assertThat(status("absent")).isEqualTo("PENDING");
        assertThat(peer.reservations).isEmpty();
        expire();
        reconcile();
        assertThat(status("absent")).isEqualTo("PENDING");
        peer.unavailable = false;
        peer.dropReleaseReply = true;
        reconcile();
        assertThat(status("absent")).isEqualTo("PENDING");
        peer.dropReleaseReply = false;
        reconcile();
        assertThat(status("absent")).isEqualTo("REJECTED");
        assertThat(peer.reservations.values()).extracting(Reservation::status).containsExactly("RELEASED");
        assertThat(peer.stock).isEqualTo(3);
    }

    @Test
    void staleSuccessfulPurchaseReplyCannotConfirmAfterCancellation() throws Exception {
        var releaseReply = new CountDownLatch(1);
        peer.replyGate = releaseReply;
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var purchase = executor.submit(() -> purchase("stale", 2));
            try {
                assertThat(peer.reserveCommitted.await(4, TimeUnit.SECONDS)).isTrue();
                assertThat(status("stale")).isEqualTo("PENDING");
                assertThat(query("""
                    SELECT count(*) FROM pg_stat_activity WHERE datname=current_database()
                    AND state='idle in transaction'
                    """)).isEqualTo("0");
                expire();
                reconcile();
                assertThat(status("stale")).isEqualTo("REJECTED");
            } finally {
                releaseReply.countDown();
            }
            assertThat(purchase.get(10, TimeUnit.SECONDS).statusCode()).isEqualTo(409);
        }
        assertThat(status("stale")).isEqualTo("REJECTED");
        assertThat(peer.stock).isEqualTo(3);
        assertThat(peer.reservations.values()).extracting(Reservation::status).containsExactly("RELEASED");
    }

    @Test
    void invalidSuccessResponsesLeaveOrdersPendingUntilVerified() throws Exception {
        peer.httpStatus = 202;
        assertThat(purchase("accepted", 1).statusCode()).isEqualTo(202);
        peer.httpStatus = 200;
        peer.wrongQuantity = true;
        assertThat(purchase("mismatch", 1).statusCode()).isEqualTo(202);
        peer.wrongQuantity = false;
        reconcile();
        assertThat(status("accepted")).isEqualTo("CONFIRMED");
        assertThat(status("mismatch")).isEqualTo("CONFIRMED");
        assertThat(peer.stock).isEqualTo(1);
    }

    private static void reconcile() {
        app.getBean(OrderService.class).reconcile();
    }

    private static void expire() throws Exception {
        execute("UPDATE orders SET requested_at = now() - interval '1 hour', next_attempt_at = now() - interval '1 hour' WHERE order_status='PENDING'");
    }

    private static String status(String key) throws Exception {
        return query("SELECT order_status FROM orders WHERE idempotency_key='" + key + "'");
    }

    private static void execute(String sql) throws Exception {
        try (var conn = DriverManager.getConnection(db.getJdbcUrl(), db.getUsername(), db.getPassword());
             var stmt = conn.createStatement()) { stmt.execute(sql); }
    }

    private static String query(String sql) throws Exception {
        try (var conn = DriverManager.getConnection(db.getJdbcUrl(), db.getUsername(), db.getPassword());
             var stmt = conn.createStatement(); var rows = stmt.executeQuery(sql)) {
            assertThat(rows.next()).isTrue();
            return rows.getString(1);
        }
    }

    private static HttpResponse<String> purchase(String key, int quantity) throws Exception {
        return post(key, """
            {"productId":1,"quantity":%d,"lockMode":"ATOMIC"}
            """.formatted(quantity));
    }

    private static HttpResponse<String> post(String key, String body) throws Exception {
        var req = HttpRequest.newBuilder(URI.create(base)).timeout(Duration.ofSeconds(15))
            .header("Content-Type", "application/json").header("Idempotency-Key", key)
            .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        return client.send(req, HttpResponse.BodyHandlers.ofString());
    }

    record Reservation(long orderId, long productId, int quantity, String status) {}

    static final class InventoryPeer {
        final Map<Long, Reservation> reservations = new HashMap<>();
        final AtomicInteger reserveCalls = new AtomicInteger();
        volatile int stock, httpStatus;
        volatile boolean wrongQuantity;
        volatile boolean unavailable, dropReserveReply, dropReleaseReply;
        volatile CountDownLatch replyGate, reserveCommitted;

        synchronized void reset() {
            httpStatus = 200;
            wrongQuantity = false;
            stock = 3;
            reservations.clear();
            reserveCalls.set(0);
            unavailable = dropReserveReply = dropReleaseReply = false;
            replyGate = null;
            reserveCommitted = new CountDownLatch(1);
        }

        void handle(HttpExchange exchange) throws IOException {
            try (exchange) {
                if (unavailable) { exchange.sendResponseHeaders(503, -1); return; }
                String path = exchange.getRequestURI().getPath();
                boolean reserve = exchange.getRequestMethod().equals("POST") && path.endsWith("/reservations");
                boolean release = path.endsWith("/release");
                JsonNode request = json.readTree(exchange.getRequestBody());
                String[] segments = path.split("/");
                long id = reserve ? request.get("orderId").asLong()
                    : Long.parseLong(segments[release ? segments.length - 2 : segments.length - 1]);
                Reservation snapshot;
                synchronized (this) {
                    snapshot = reservations.get(id);
                    if (reserve || release) {
                        int quantity = request.get("quantity").asInt();
                        long product = request.get("productId").asLong();
                        if (snapshot != null && (snapshot.productId() != product || snapshot.quantity() != quantity)) {
                            exchange.sendResponseHeaders(409, -1); return;
                        }
                        if (snapshot == null) {
                            String state = release ? "RELEASED" : stock >= quantity ? "RESERVED" : "REJECTED";
                            snapshot = new Reservation(id, product, quantity, state);
                            if (state.equals("RESERVED")) stock -= quantity;
                        } else if (release && snapshot.status().equals("RESERVED")) {
                            stock += quantity;
                            snapshot = new Reservation(id, product, quantity, "RELEASED");
                        }
                        reservations.put(id, snapshot);
                    }
                }
                if (reserve) {
                    reserveCalls.incrementAndGet();
                    reserveCommitted.countDown();
                    if (replyGate != null) {
                        try { replyGate.await(12, TimeUnit.SECONDS); }
                        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    }
                    if (dropReserveReply) return;
                }
                if (release && dropReleaseReply) return;
                if (snapshot == null) { exchange.sendResponseHeaders(404, -1); return; }
                if (wrongQuantity) snapshot = new Reservation(id, snapshot.productId(), 99, snapshot.status());
                byte[] bytes = json.writeValueAsBytes(snapshot);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(httpStatus, bytes.length);
                exchange.getResponseBody().write(bytes);
            }
        }
    }
}
