package com.hthien.inventory.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class ReservationIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.9")
        .withDatabaseName("inventory_db");
    @Container
    static final GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    private static final String MODE = System.getProperty("inventory.test.lock-mode", "ATOMIC");
    private static final String RESERVATIONS = "/internal/v1/reservations";
    private static final String INVENTORY = "/internal/v1/inventory/";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private static final List<Process> PROCESSES = new ArrayList<>();
    private static final List<String> URLS = new ArrayList<>();

    @BeforeAll
    static void startTwoJvms() throws Exception {
        Path logs = Path.of("target", "inventory-integration").toAbsolutePath();
        Files.createDirectories(logs);
        for (String instance : List.of("a", "b")) {
            Path log = logs.resolve(instance + ".log");
            String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
            Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", executable).toString(),
                "-Xmx192m", "-XX:ActiveProcessorCount=4", "-cp",
                System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                "com.hthien.inventory.InventoryApplication", "--server.port=0",
                "--spring.datasource.url=" + postgres.getJdbcUrl(),
                "--spring.datasource.username=" + postgres.getUsername(),
                "--spring.datasource.password=" + postgres.getPassword(), "--inventory.lock-mode=" + MODE,
                "--inventory.redis-address=redis://" + redis.getHost() + ":" + redis.getFirstMappedPort(),
                "--logging.level.root=WARN",
                "--logging.level.org.springframework.boot.web.embedded.tomcat.TomcatWebServer=INFO")
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
            PROCESSES.add(process);
            URLS.add(awaitHealthy(process, log));
        }
        assertThat(PROCESSES.get(0).pid()).isNotEqualTo(PROCESSES.get(1).pid());
    }

    private static String awaitHealthy(Process process, Path log) throws Exception {
        Pattern portPattern = Pattern.compile("Tomcat started on port (\\d+)");
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (process.isAlive() && System.nanoTime() < deadline) {
            var port = portPattern.matcher(Files.readString(log));
            if (port.find()) {
                String url = "http://127.0.0.1:" + port.group(1);
                if (send(url, "GET", "/actuator/health", null).statusCode() == 200) {
                    return url;
                }
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Inventory JVM did not start.\n" + Files.readString(log));
    }

    @AfterAll
    static void stopTwoJvms() throws Exception {
        for (Process process : PROCESSES) {
            process.destroy();
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                assertThat(process.waitFor(5, TimeUnit.SECONDS)).isTrue();
            }
        }
    }

    @BeforeEach
    void resetDatabase() throws Exception {
        execute("TRUNCATE stock_reservations, inventory RESTART IDENTITY");
    }

    @Test
    void duplicateReserveAndReleaseAcrossTwoJvmsMutateStockOnce() throws Exception {
        seed(11, 10);
        var reserves = race(IntStream.range(0, 24)
            .mapToObj(i -> (Callable<HttpResponse<String>>) () -> reserve(i, 1, 11, 3)).toList());
        for (var response : reserves) {
            assertStatus(response, "RESERVED");
        }
        assertThat(stock(11)).isEqualTo(7);
        assertThat(scalar("SELECT count(*) FROM stock_reservations")).isEqualTo(1);
        assertStatus(send(URLS.get(1), "GET", RESERVATIONS + "/1", null), "RESERVED");

        var releases = race(IntStream.range(0, 24)
            .mapToObj(i -> (Callable<HttpResponse<String>>) () -> release(i, 1, 11, 3)).toList());
        for (var response : releases) {
            assertStatus(response, "RELEASED");
        }
        assertStatus(reserve(0, 1, 11, 3), "RELEASED");
        assertThat(stock(11)).isEqualTo(10);
        assertThat(scalar("SELECT count(*) FROM stock_reservations WHERE status = 'RELEASED'")).isEqualTo(1);
    }

    @Test
    void concurrentPayloadMismatchLeavesOnlyWinningPayloadAndDeduction() throws Exception {
        seed(11, 10);
        seed(12, 10);
        var results = race(List.of(() -> reserve(0, 2, 11, 2), () -> reserve(1, 2, 12, 4)));
        assertThat(results).extracting(HttpResponse::statusCode).containsExactlyInAnyOrder(200, 409);
        JsonNode winner = assertStatus(send(URLS.get(0), "GET", RESERVATIONS + "/2", null), "RESERVED");
        long product = winner.path("productId").asLong();
        int quantity = winner.path("quantity").asInt();
        assertThat(stock(product)).isEqualTo(10 - quantity);
        assertThat(stock(product == 11 ? 12 : 11)).isEqualTo(10);
        assertThat(release(1, 2, product, quantity + 1).statusCode()).isEqualTo(409);
        assertThat(stock(product)).isEqualTo(10 - quantity);
        assertThat(scalar("SELECT count(*) FROM stock_reservations")).isEqualTo(1);
    }

    @Test
    void rejectionAndReleaseBeforeReservePersistEvenAfterStockSetup() throws Exception {
        assertThat(send(URLS.get(0), "GET", RESERVATIONS + "/3", null).statusCode()).isEqualTo(404);
        assertStatus(release(0, 3, 11, 3), "RELEASED");
        assertStatus(reserve(1, 4, 11, 3), "REJECTED");
        seed(11, 5);
        assertStatus(reserve(1, 3, 11, 3), "RELEASED");
        assertStatus(release(1, 3, 11, 3), "RELEASED");
        assertThat(reserve(1, 3, 11, 4).statusCode()).isEqualTo(409);
        assertStatus(reserve(0, 4, 11, 3), "REJECTED");
        assertStatus(release(0, 4, 11, 3), "REJECTED");
        assertThat(stock(11)).isEqualTo(5);

        assertStatus(reserve(0, 5, 11, 6), "REJECTED");
        seed(11, 7);
        assertStatus(reserve(1, 5, 11, 6), "REJECTED");
        assertThat(stock(11)).isEqualTo(7);
        assertThat(scalar("SELECT count(*) FROM stock_reservations WHERE status = 'REJECTED'")).isEqualTo(2);
    }

    @Test
    void racingReserveAndReleaseAlwaysLeavesReleasedAndRestoresInitialStock() throws Exception {
        seed(11, 10);
        for (int order = 10; order < 20; order++) {
            int id = order;
            var results = race(List.of(() -> reserve(0, id, 11, 3), () -> release(1, id, 11, 3)));
            assertThat(results.get(0).statusCode()).isEqualTo(200);
            assertThat(JSON.readTree(results.get(0).body()).path("status").asText()).isIn("RESERVED", "RELEASED");
            assertStatus(results.get(1), "RELEASED");
        }
        assertThat(stock(11)).isEqualTo(10);
        assertThat(scalar("SELECT count(*) FROM stock_reservations WHERE status = 'RELEASED'")).isEqualTo(10);
    }

    @Test
    void distinctOrdersNeverOversellAndCommittedRowsReconcileWithStock() throws Exception {
        seed(11, 10);
        var responses = race(IntStream.range(0, 32)
            .mapToObj(i -> (Callable<HttpResponse<String>>) () -> reserve(i, 100 + i, 11, 3)).toList());
        for (var response : responses) {
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(JSON.readTree(response.body()).path("status").asText()).isIn("RESERVED", "REJECTED");
        }
        long reserved = scalar("SELECT count(*) FROM stock_reservations WHERE status = 'RESERVED'");
        assertThat(reserved).isBetween(1L, 3L);
        if (!MODE.equals("OPTIMISTIC")) {
            assertThat(reserved).isEqualTo(3);
        }
        assertThat(stock(11)).isEqualTo(10 - 3 * reserved);
        assertThat(scalar("SELECT count(*) FROM stock_reservations")).isEqualTo(32);
    }

    @Test
    void commitFailureRollsBackBothReserveAndRelease() throws Exception {
        seed(11, 10);
        execute("""
            CREATE FUNCTION fail_inventory_test_commit() RETURNS trigger LANGUAGE plpgsql AS $$
            BEGIN RAISE EXCEPTION 'injected commit failure'; END; $$
            """);
        try {
            execute("""
                CREATE CONSTRAINT TRIGGER fail_inventory_test_commit
                AFTER INSERT OR UPDATE ON stock_reservations DEFERRABLE INITIALLY DEFERRED
                FOR EACH ROW WHEN ((NEW.order_id = 30 AND NEW.status = 'RESERVED')
                               OR (NEW.order_id = 31 AND NEW.status = 'RELEASED'))
                EXECUTE FUNCTION fail_inventory_test_commit()
                """);
            assertThat(reserve(0, 30, 11, 3).statusCode()).isEqualTo(500);
            assertThat(stock(11)).isEqualTo(10);
            assertThat(scalar("SELECT count(*) FROM stock_reservations WHERE order_id = 30")).isZero();

            assertStatus(reserve(1, 31, 11, 3), "RESERVED");
            assertThat(release(0, 31, 11, 3).statusCode()).isEqualTo(500);
            assertThat(stock(11)).isEqualTo(7);
            assertThat(scalar("SELECT count(*) FROM stock_reservations WHERE order_id = 31 AND status = 'RESERVED'"))
                .isEqualTo(1);
        } finally {
            execute("DROP FUNCTION fail_inventory_test_commit() CASCADE");
        }
        assertStatus(release(1, 31, 11, 3), "RELEASED");
        assertStatus(reserve(0, 30, 11, 3), "RESERVED");
        assertThat(stock(11)).isEqualTo(7);
    }

    @Test
    void validationAndAdminResetPreserveRoomForRelease() throws Exception {
        seed(11, 8);
        assertThat(reserve(0, 0, 11, 1).statusCode()).isEqualTo(400);
        assertThat(reserve(0, 7, 11, 0).statusCode()).isEqualTo(400);
        assertThat(send(URLS.get(1), "GET", RESERVATIONS + "/0", null).statusCode()).isEqualTo(400);
        assertThat(send(URLS.get(0), "POST", RESERVATIONS, """
            {"orderId": 7, "productId": 11, "quantity": 1.9}
            """).statusCode()).isEqualTo(400);
        assertThat(scalar("SELECT count(*) FROM stock_reservations")).isZero();

        assertStatus(reserve(0, 7, 11, 2), "RESERVED");
        assertThat(send(URLS.get(1), "PUT", INVENTORY + "11", "{\"stock\":2147483647}").statusCode())
            .isEqualTo(409);
        assertThat(stock(11)).isEqualTo(6);
        assertThat(seed(11, Integer.MAX_VALUE - 2).path("stock").asInt()).isEqualTo(Integer.MAX_VALUE - 2);
        assertStatus(release(1, 7, 11, 2), "RELEASED");
        assertThat(stock(11)).isEqualTo(Integer.MAX_VALUE);
        assertThat(JSON.readTree(send(URLS.get(0), "GET", INVENTORY + "11", null).body())
            .path("stock").asInt()).isEqualTo(Integer.MAX_VALUE);
    }

    @Test
    void onlyVersionedReservationRoutesAreExposed() throws Exception {
        assertThat(send(URLS.get(0), "POST", "/internal/reservations",
            "{\"orderId\":8,\"productId\":11,\"quantity\":1}").statusCode()).isEqualTo(404);
        assertThat(send(URLS.get(0), "GET", "/internal/reservations/8", null).statusCode()).isEqualTo(404);
        assertThat(send(URLS.get(0), "POST", "/internal/reservations/8/release",
            "{\"productId\":11,\"quantity\":1}").statusCode()).isEqualTo(404);
        assertThat(scalar("SELECT count(*) FROM stock_reservations")).isZero();
    }

    @Test
    void redisOutageCannotLeavePartiallyCommittedReservations() throws Exception {
        long productId = 19;
        seed(productId, 10);
        redis.getDockerClient().pauseContainerCmd(redis.getContainerId()).exec();
        try {
            var response = reserve(0, 9, productId, 3);
            if (MODE.equals("REDIS")) {
                assertThat(response.statusCode()).as(response.body()).isEqualTo(503);
                assertThat(stock(productId)).isEqualTo(10);
                assertThat(scalar("SELECT count(*) FROM stock_reservations")).isZero();
            } else {
                assertStatus(response, "RESERVED");
                assertThat(stock(productId)).isEqualTo(7);
                assertThat(scalar("SELECT count(*) FROM stock_reservations")).isEqualTo(1);
            }
        } finally {
            redis.getDockerClient().unpauseContainerCmd(redis.getContainerId()).exec();
        }

        if (MODE.equals("REDIS")) {
            // Redis can process the timed-out acquisition after unpause. Its unrenewed lock must expire.
            await().pollInterval(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(40)).untilAsserted(
                () -> assertStatus(reserve(1, 9, productId, 3), "RESERVED"));
        } else {
            assertStatus(reserve(1, 9, productId, 3), "RESERVED");
        }
        assertThat(stock(productId)).isEqualTo(7);
        assertThat(scalar("SELECT count(*) FROM stock_reservations WHERE status = 'RESERVED'")).isEqualTo(1);
    }

    private static JsonNode seed(long productId, int stock) throws Exception {
        var response = send(URLS.get(0), "PUT", INVENTORY + productId, "{\"stock\":" + stock + "}");
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return JSON.readTree(response.body());
    }

    private static HttpResponse<String> reserve(int node, int order, long product, int quantity) throws Exception {
        return send(URLS.get(node % 2), "POST", RESERVATIONS,
            "{\"orderId\":%d,\"productId\":%d,\"quantity\":%d}".formatted(order, product, quantity));
    }

    private static HttpResponse<String> release(int node, int order, long product, int quantity) throws Exception {
        return send(URLS.get(node % 2), "POST", RESERVATIONS + "/" + order + "/release",
            "{\"productId\":%d,\"quantity\":%d}".formatted(product, quantity));
    }

    private static JsonNode assertStatus(HttpResponse<String> response, String status) throws Exception {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        JsonNode body = JSON.readTree(response.body());
        assertThat(body.path("status").asText()).isEqualTo(status);
        return body;
    }

    private static HttpResponse<String> send(String url, String method, String path, String body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(url + path)).timeout(Duration.ofSeconds(15))
            .header("Content-Type", "application/json")
            .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
            .build();
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static List<HttpResponse<String>> race(List<Callable<HttpResponse<String>>> tasks) throws Exception {
        var ready = new CountDownLatch(tasks.size());
        var start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = tasks.stream().map(task -> executor.submit(() -> {
                ready.countDown();
                if (!start.await(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("Race did not start");
                }
                return task.call();
            })).toList();
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<HttpResponse<String>> results = new ArrayList<>();
            for (var future : futures) {
                results.add(future.get(20, TimeUnit.SECONDS));
            }
            return results;
        }
    }

    private static long stock(long productId) throws Exception {
        return scalar("SELECT stock FROM inventory WHERE product_id = " + productId);
    }

    private static long scalar(String sql) throws Exception {
        try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var statement = connection.createStatement();
             var result = statement.executeQuery(sql)) {
            assertThat(result.next()).isTrue();
            return result.getLong(1);
        }
    }

    private static void execute(String sql) throws Exception {
        try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
