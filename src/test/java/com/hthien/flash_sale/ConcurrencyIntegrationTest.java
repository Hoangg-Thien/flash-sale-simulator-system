package com.hthien.flash_sale;

import static org.assertj.core.api.Assertions.*;

import java.math.BigDecimal;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.hthien.flash_sale.dto.request.CreateOrderRequest;
import com.hthien.flash_sale.dto.request.CreateSimulationRequest;
import com.hthien.flash_sale.dto.response.OrderResponse;
import com.hthien.flash_sale.dto.response.SimulationRunResponse;
import com.hthien.flash_sale.entity.Inventory;
import com.hthien.flash_sale.entity.Product;
import com.hthien.flash_sale.enums.OrderStatus;
import com.hthien.flash_sale.exception.InsufficientStockException;
import com.hthien.flash_sale.repository.InventoryRepository;
import com.hthien.flash_sale.repository.OrderRepository;
import com.hthien.flash_sale.repository.ProductRepository;
import com.hthien.flash_sale.repository.SimulationRequestRepository;
import com.hthien.flash_sale.repository.SimulationRunRepository;
import com.hthien.flash_sale.service.OrderService;
import com.hthien.flash_sale.service.SimulationService;

@SpringBootTest
@Testcontainers
class ConcurrencyIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres =
        new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("testdb")
            .withUsername("test")
            .withPassword("test");

    @Container
    static GenericContainer<?> redis =
        new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", redis::getFirstMappedPort);
    }

    @Autowired SimulationService simulationService;
    @Autowired OrderService orderService;
    @Autowired ProductRepository productRepository;
    @Autowired InventoryRepository inventoryRepository;
    @Autowired SimulationRunRepository simulationRunRepository;
    @Autowired SimulationRequestRepository simulationRequestRepository;
    @Autowired OrderRepository orderRepository;

    private Product testProduct;

    @BeforeEach
    void setUp() {
        simulationRequestRepository.deleteAll();
        simulationRunRepository.deleteAll();
        orderRepository.deleteAll();
        inventoryRepository.deleteAll();
        productRepository.deleteAll();

        testProduct = productRepository.save(
            Product.builder()
                .name("MacBook Pro M3")
                .price(new BigDecimal("45000000"))
                .build()
        );
        inventoryRepository.save(
            Inventory.builder()
                .product(testProduct)
                .stock(10)
                .build()
        );
    }

    // ============================================================
    // TEST CASE 6: Stock = 10, 20 concurrent threads với PESSIMISTIC LOCK
    // ============================================================
    @Test
    @DisplayName("Case 6: Stock=10, 20 thread, PESSIMISTIC lock → đúng 10 success, 10 failed, finalStock=0")
    void concurrent_withPessimisticLock_multiStock_shouldSucceedExactlyStockCount() {
        CreateSimulationRequest request = new CreateSimulationRequest();
        request.setProductId(testProduct.getId());
        request.setInitialStock(10);
        request.setConcurrentUsers(20);
        request.setLockMode("PESSIMISTIC");

        SimulationRunResponse response = simulationService.runSimulation(request);

        assertThat(response.getOversellingDetected()).isFalse();
        assertThat(response.getSuccessCount()).isEqualTo(10);
        assertThat(response.getFailedCount()).isEqualTo(10);
        assertThat(response.getFinalStock()).isEqualTo(0);
    }

    // ============================================================
    // TEST CASE 6b: Stock = 10, 20 concurrent threads với REDIS LOCK
    // ============================================================
    @Test
    @DisplayName("Case 6b: Stock=10, 20 thread, REDIS lock → đúng 10 success, 10 failed, finalStock=0")
    void concurrent_withRedisLock_multiStock_shouldSucceedExactlyStockCount() {
        CreateSimulationRequest request = new CreateSimulationRequest();
        request.setProductId(testProduct.getId());
        request.setInitialStock(10);
        request.setConcurrentUsers(20);
        request.setLockMode("REDIS");

        SimulationRunResponse response = simulationService.runSimulation(request);

        assertThat(response.getOversellingDetected()).isFalse();
        assertThat(response.getSuccessCount()).isEqualTo(10);
        assertThat(response.getFailedCount()).isEqualTo(10);
        assertThat(response.getFinalStock()).isEqualTo(0);
    }

    // ============================================================
    // TEST CASE 7: Stock = 0, 20 concurrent threads
    // ============================================================
    @Test
    @DisplayName("Case 7: Stock=0, 20 thread → 0 success, 20 failed, tất cả InsufficientStockException")
    void concurrent_withZeroStock_shouldAllFail() {
        CreateSimulationRequest request = new CreateSimulationRequest();
        request.setProductId(testProduct.getId());
        request.setInitialStock(0);
        request.setConcurrentUsers(20);
        request.setLockMode("REDIS");

        SimulationRunResponse response = simulationService.runSimulation(request);

        assertThat(response.getOversellingDetected()).isFalse();
        assertThat(response.getSuccessCount()).isEqualTo(0);
        assertThat(response.getFailedCount()).isEqualTo(20);
        assertThat(response.getFinalStock()).isEqualTo(0);
    }

    // ============================================================
    // TEST CASE 12: Mua đồng thời 2 PRODUCT KHÁC NHAU (Lock Isolation)
    // ============================================================
    @Test
    @DisplayName("Case 12: Mua đồng thời 2 sản phẩm khác nhau → cả 2 thành công độc lập (Lock đúng phạm vi productId)")
    void concurrent_differentProducts_shouldNotBlockEachOther() throws Exception {
        // Product A (testProduct) đã có stock = 10
        // Tạo thêm Product B (stock = 1)
        Product productB = productRepository.save(
            Product.builder()
                .name("iPad Air M2")
                .price(new BigDecimal("16990000"))
                .build()
        );
        inventoryRepository.save(
            Inventory.builder()
                .product(productB)
                .stock(1)
                .build()
        );

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(2);

        AtomicInteger successCount = new AtomicInteger(0);

        // Thread 1: Mua Product A
        executor.submit(() -> {
            try {
                startLatch.await();
                CreateOrderRequest reqA = new CreateOrderRequest();
                reqA.setProductId(testProduct.getId());
                reqA.setQuantity(1);
                reqA.setLockMode("REDIS");
                OrderResponse resA = orderService.createOrder(reqA, null);
                if (resA.getOrderStatus() == OrderStatus.SUCCESS) {
                    successCount.incrementAndGet();
                }
            } catch (Exception e) {
                // Ignore
            } finally {
                doneLatch.countDown();
            }
        });

        // Thread 2: Mua Product B
        executor.submit(() -> {
            try {
                startLatch.await();
                CreateOrderRequest reqB = new CreateOrderRequest();
                reqB.setProductId(productB.getId());
                reqB.setQuantity(1);
                reqB.setLockMode("REDIS");
                OrderResponse resB = orderService.createOrder(reqB, null);
                if (resB.getOrderStatus() == OrderStatus.SUCCESS) {
                    successCount.incrementAndGet();
                }
            } catch (Exception e) {
                // Ignore
            } finally {
                doneLatch.countDown();
            }
        });

        // Bắt đầu 2 thread cùng lúc
        startLatch.countDown();
        boolean completed = doneLatch.await(5, TimeUnit.SECONDS);

        executor.shutdown();

        assertThat(completed).isTrue();
        assertThat(successCount.get()).isEqualTo(2);

        // Verify stock cả 2 product
        Inventory invA = inventoryRepository.findByProductId(testProduct.getId()).get();
        Inventory invB = inventoryRepository.findByProductId(productB.getId()).get();
        assertThat(invA.getStock()).isEqualTo(9); // 10 - 1
        assertThat(invB.getStock()).isEqualTo(0); // 1 - 1
    }
}
