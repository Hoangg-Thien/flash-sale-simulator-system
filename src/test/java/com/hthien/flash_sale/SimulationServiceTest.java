package com.hthien.flash_sale;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.hthien.flash_sale.dto.request.CreateSimulationRequest;
import com.hthien.flash_sale.dto.response.SimulationRunResponse;
import com.hthien.flash_sale.entity.Inventory;
import com.hthien.flash_sale.entity.Product;
import com.hthien.flash_sale.repository.InventoryRepository;
import com.hthien.flash_sale.repository.OrderRepository;
import com.hthien.flash_sale.repository.ProductRepository;
import com.hthien.flash_sale.repository.SimulationRequestRepository;
import com.hthien.flash_sale.repository.SimulationRunRepository;
import com.hthien.flash_sale.service.SimulationService;

@SpringBootTest
@Testcontainers
class SimulationServiceTest {

    @Container
    static PostgreSQLContainer<?> postgres =
        new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("testdb")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.enabled", () -> "false");
    }

    @Autowired SimulationService simulationService;
    @Autowired ProductRepository productRepository;
    @Autowired InventoryRepository inventoryRepository;
    @Autowired SimulationRunRepository simulationRunRepository;
    @Autowired SimulationRequestRepository simulationRequestRepository;
    @Autowired OrderRepository orderRepository;

    private Product testProduct;

    @BeforeEach
    void setUp() {
        // Clear theo đúng thứ tự FK: simulation_requests → simulation_runs → orders → inventory → product
        simulationRequestRepository.deleteAll();
        simulationRunRepository.deleteAll();
        orderRepository.deleteAll();
        inventoryRepository.deleteAll();
        productRepository.deleteAll();

        testProduct = productRepository.save(
            Product.builder()
                .name("Flash Sale Item")
                .price(new BigDecimal("999000"))
                .build()
        );

        inventoryRepository.save(
            Inventory.builder()
                .product(testProduct)
                .stock(1)
                .build()
        );
    }

    // ============================================================
    // TEST 1: BẮT BUỘC — chứng minh race condition (test phải PASS)
    // ============================================================
    @Test
    @DisplayName("[MUST PASS] stock=1, 20 thread, mode=NONE → overselling xảy ra (finalStock<0 OR successCount>1)")
    void runSimulation_withNoLock_shouldDetectOverselling() {
        // Arrange
        CreateSimulationRequest request = buildSimRequest(
            testProduct.getId(), 1, 20, "NONE");

        // Act
        SimulationRunResponse response = simulationService.runSimulation(request);

        // Assert: oversell phải xảy ra (ít nhất 1 trong 2 điều kiện)
        // Lưu ý: test này được THIẾT KẾ ĐỂ PASS — nếu không oversell thì có vấn đề
        boolean oversellDetected =
            response.getFinalStock() < 0 ||
            response.getSuccessCount() > response.getInitialStock();

        assertThat(oversellDetected)
            .as("""
                BUG CHƯA XẢY RA: finalStock=%d, successCount=%d, initialStock=%d.
                Race condition không xảy ra lần này (có thể flaky — thử lại).
                """, response.getFinalStock(), response.getSuccessCount(), response.getInitialStock())
            .isTrue();

        // Verify database đồng bộ với response
        assertThat(response.getSuccessCount() + response.getFailedCount())
            .isEqualTo(20); // tổng luôn = số thread

        assertThat(response.getRequests()).hasSize(20); // lưu đủ 20 SimulationRequest
    }

    // ============================================================
    // TEST 2: Lặp lại 3 lần — bug phải xảy ra ổn định
    // ============================================================
    @Test
    @DisplayName("[MUST PASS] Lặp lại 3 lần — race condition xảy ra ≥ 1 lần (không phải may rủi)")
    void runSimulation_withNoLock_reproducedAtLeastOnce_inThreeRuns() {
        int oversellCount = 0;

        for (int i = 0; i < 3; i++) {
            // Reset data giữa các lần chạy
            simulationRequestRepository.deleteAll();
            simulationRunRepository.deleteAll();
            orderRepository.deleteAll();

            // Reset stock
            Inventory inventory = inventoryRepository.findByProductId(testProduct.getId()).get();
            inventory.setStock(1);
            inventoryRepository.save(inventory);

            CreateSimulationRequest request = buildSimRequest(
                testProduct.getId(), 1, 20, "NONE");
            SimulationRunResponse response = simulationService.runSimulation(request);

            if (response.getFinalStock() < 0 || response.getSuccessCount() > 1) {
                oversellCount++;
            }
        }

        assertThat(oversellCount)
            .as("Chạy 3 lần, bug phải xảy ra ít nhất 1 lần. Nếu 0 lần thì race condition không ổn định.")
            .isGreaterThanOrEqualTo(1);
    }

    // ============================================================
    // TEST 3: stock=10, 20 thread — verify stats đầy đủ
    // ============================================================
    @Test
    @DisplayName("stock=10, 20 thread, mode=NONE → successCount+failedCount=20, stats có giá trị")
    void runSimulation_stats_shouldBeCalculatedCorrectly() {
        // Arrange: reset stock = 10
        Inventory inventory = inventoryRepository.findByProductId(testProduct.getId()).get();
        inventory.setStock(10);
        inventoryRepository.save(inventory);

        CreateSimulationRequest request = buildSimRequest(
            testProduct.getId(), 10, 20, "NONE");

        // Act
        SimulationRunResponse response = simulationService.runSimulation(request);

        // Assert tổng hợp
        assertThat(response.getSuccessCount() + response.getFailedCount()).isEqualTo(20);
        assertThat(response.getRequests()).hasSize(20);

        // Stats phải có giá trị
        assertThat(response.getAvgLatencyMs()).isGreaterThan(BigDecimal.ZERO);
        assertThat(response.getThroughputRps()).isGreaterThan(BigDecimal.ZERO);
        assertThat(response.getExecutionTimeMs()).isGreaterThan(0L);

        // Verify SimulationRun được lưu đúng vào DB
        assertThat(simulationRunRepository.findById(response.getSimulationRunId()))
            .isPresent();
        assertThat(simulationRequestRepository
            .findBySimulationRunId(response.getSimulationRunId()))
            .hasSize(20);
    }

    // Helper
    private CreateSimulationRequest buildSimRequest(
            Long productId, int initialStock, int concurrentUsers, String lockMode) {
        CreateSimulationRequest request = new CreateSimulationRequest();
        request.setProductId(productId);
        request.setInitialStock(initialStock);
        request.setConcurrentUsers(concurrentUsers);
        request.setLockMode(lockMode);
        return request;
    }
}
