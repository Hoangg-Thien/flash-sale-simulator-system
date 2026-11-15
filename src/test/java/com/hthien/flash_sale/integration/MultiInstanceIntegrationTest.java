package com.hthien.flash_sale.integration;

import static org.assertj.core.api.Assertions.*;

import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.fasterxml.jackson.databind.ObjectMapper;

@Testcontainers
class MultiInstanceIntegrationTest {

    @Container static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");
    @Container static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
    private static final ObjectMapper json = new ObjectMapper();
    private static final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private static final List<Process> processes = new ArrayList<>();
    private static final List<String> urls = new ArrayList<>();

    @BeforeAll
    static void startTwoJvms() throws Exception {
        try {
            for (String instance : List.of("app-a", "app-b")) {
                Path log = Path.of("target", "multi-instance", instance + ".log").toAbsolutePath();
                Files.createDirectories(log.getParent());
                String java = Path.of(System.getProperty("java.home"), "bin",
                    System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
                Process process = new ProcessBuilder(java, "-Xmx256m", "-cp",
                    System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                    "com.hthien.flash_sale.FlashSaleApplication", "--server.port=0",
                    "--spring.datasource.url=" + postgres.getJdbcUrl(),
                    "--spring.datasource.username=" + postgres.getUsername(),
                    "--spring.datasource.password=" + postgres.getPassword(),
                    "--spring.data.redis.host=" + redis.getHost(),
                    "--spring.data.redis.port=" + redis.getFirstMappedPort(),
                    "--app.instance-id=" + instance, "--spring.jpa.show-sql=false",
                    "--logging.level.root=WARN",
                    "--logging.level.org.springframework.boot.web.embedded.tomcat.TomcatWebServer=INFO")
                    .redirectErrorStream(true).redirectOutput(log.toFile()).start();
                processes.add(process);
                urls.add(awaitReady(process, log));
            }
            assertThat(processes.get(0).pid()).isNotEqualTo(processes.get(1).pid());
        } catch (Exception | AssertionError failure) {
            stopTwoJvms();
            throw failure;
        }
    }

    @AfterAll
    static void stopTwoJvms() throws Exception {
        for (Process process : processes) {
            process.destroy();
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                assertThat(process.waitFor(10, TimeUnit.SECONDS)).isTrue();
            }
        }
    }

    private static String awaitReady(Process process, Path log) throws Exception {
        Pattern portPattern = Pattern.compile("Tomcat started on port (\\d+)");
        long deadline = System.nanoTime() + Duration.ofSeconds(75).toNanos();
        while (System.nanoTime() < deadline && process.isAlive()) {
            var match = portPattern.matcher(Files.readString(log));
            if (match.find()) {
                String url = "http://127.0.0.1:" + match.group(1);
                try {
                    var response = client.send(HttpRequest.newBuilder(URI.create(url + "/actuator/health"))
                        .timeout(Duration.ofSeconds(2)).GET().build(), HttpResponse.BodyHandlers.ofString());
                    if (response.statusCode() == 200 && json.readTree(response.body()).path("status").asText().equals("UP")) {
                        return url;
                    }
                } catch (java.io.IOException ignored) {
                    // Tomcat may bind before the health endpoint is ready.
                }
            }
            Thread.sleep(100);
        }
        throw new IllegalStateException("Child JVM did not become healthy: " + log + "\n" + Files.readString(log));
    }

    static Stream<Arguments> safeCases() {
        return Stream.of("ATOMIC", "OPTIMISTIC", "PESSIMISTIC", "REDIS").flatMap(mode ->
            Stream.of(Arguments.of(mode, 0, 1), Arguments.of(mode, 1, 1),
                Arguments.of(mode, 10, 1), Arguments.of(mode, 10, 3)));
    }

    @ParameterizedTest
    @MethodSource("safeCases")
    void safeModes_conservePersistedUnitsAcrossTwoJvms(String mode, int stock, int quantity) throws Exception {
        long productId = createProduct(stock);
        List<Purchase> purchases = purchaseAcrossNodes(productId, quantity, mode);
        long confirmed = purchases.stream().filter(p -> p.status() == 201).count();
        assertThat(confirmed).isLessThanOrEqualTo(stock / quantity);
        if (!mode.equals("OPTIMISTIC")) {
            assertThat(confirmed).isEqualTo(stock / quantity);
        }
        assertPersisted(productId, stock, quantity, confirmed, true);
    }

    @Test
    void none_isAnIsolatedLostUpdateDemonstrationAcrossTwoJvms() throws Exception {
        boolean reproduced = false;
        for (int attempt = 0; attempt < 5 && !reproduced; attempt++) {
            long productId = createProduct(1);
            List<Purchase> purchases = purchaseAcrossNodes(productId, 1, "NONE");
            long confirmed = purchases.stream().filter(p -> p.status() == 201).count();
            assertPersisted(productId, 1, 1, confirmed, false);
            reproduced = confirmed > 1;
        }
        assertThat(reproduced).as("NONE lost update must be observed in at least one of five isolated attempts").isTrue();
    }

    private long createProduct(int stock) throws Exception {
        String body = json.writeValueAsString(Map.of("name", "multi-jvm-" + UUID.randomUUID(),
            "price", 10, "initialStock", stock));
        var response = client.send(HttpRequest.newBuilder(URI.create(urls.get(0) + "/api/v1/products"))
            .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body))
            .timeout(Duration.ofSeconds(10)).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(201);
        return json.readTree(response.body()).path("id").asLong();
    }

    private record Purchase(int node, int status) {}

    private List<Purchase> purchaseAcrossNodes(long productId, int quantity, String mode) throws Exception {
        ExecutorService workers = Executors.newFixedThreadPool(20);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Purchase>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < 20; i++) {
                int node = i % 2;
                String requestId = UUID.randomUUID().toString();
                futures.add(workers.submit(() -> {
                    assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                    String body = json.writeValueAsString(Map.of("productId", productId, "quantity", quantity, "lockMode", mode));
                    var response = client.send(HttpRequest.newBuilder(URI.create(urls.get(node) + "/api/v1/orders"))
                        .header("Content-Type", "application/json").header("X-Request-Id", requestId)
                        .header("Idempotency-Key", requestId).POST(HttpRequest.BodyPublishers.ofString(body))
                        .timeout(Duration.ofSeconds(15)).build(), HttpResponse.BodyHandlers.ofString());
                    assertThat(response.statusCode()).as(response.body()).isIn(201, 409);
                    assertThat(response.headers().firstValue("X-Instance-Id")).contains(node == 0 ? "app-a" : "app-b");
                    assertThat(response.headers().firstValue("X-Request-Id")).contains(requestId);
                    if (response.statusCode() == 409) {
                        assertThat(json.readTree(response.body()).path("requestId").asText()).isEqualTo(requestId);
                    }
                    return new Purchase(node, response.statusCode());
                }));
            }
            start.countDown();
            List<Purchase> results = new ArrayList<>();
            for (Future<Purchase> future : futures) {
                results.add(future.get(20, TimeUnit.SECONDS));
            }
            assertThat(results.stream().filter(p -> p.node() == 0).count()).isEqualTo(10);
            assertThat(results.stream().filter(p -> p.node() == 1).count()).isEqualTo(10);
            return results;
        } finally {
            workers.shutdownNow();
            assertThat(workers.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private void assertPersisted(long productId, int initial, int quantity, long confirmed, boolean safe) throws Exception {
        try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var query = connection.prepareStatement("""
                 SELECT i.stock,
                   (SELECT count(*) FROM orders o WHERE o.product_id=i.product_id) AS orders,
                   (SELECT count(DISTINCT o.idempotency_key) FROM orders o WHERE o.product_id=i.product_id) AS keys,
                   (SELECT coalesce(sum(oi.quantity),0) FROM order_items oi JOIN orders o ON o.id=oi.order_id
                    WHERE o.product_id=i.product_id AND o.order_status='SUCCESS') AS units
                 FROM inventory i WHERE i.product_id=?
                 """)) {
            query.setLong(1, productId);
            try (var result = query.executeQuery()) {
                assertThat(result.next()).isTrue();
                assertThat(result.getLong("orders")).isEqualTo(confirmed);
                assertThat(result.getLong("keys")).isEqualTo(confirmed);
                assertThat(result.getLong("units")).isEqualTo(confirmed * quantity);
                assertThat(result.getInt("stock")).isNotNegative();
                if (safe) {
                    assertThat(result.getLong("stock")).isEqualTo(initial - result.getLong("units"));
                }
            }
        }
        for (String url : urls) {
            var response = client.send(HttpRequest.newBuilder(URI.create(url + "/api/v1/products/" + productId))
                .timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            if (safe) {
                assertThat(json.readTree(response.body()).path("stock").asLong()).isEqualTo(initial - confirmed * quantity);
            }
        }
    }
}
