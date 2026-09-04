package com.hthien.flash_sale;

import static org.assertj.core.api.Assertions.*;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

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
class SimulationComparisonTest {

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

    /**
     * Test này chạy tất cả 4 mode và in bảng so sánh.
     * Dùng để demo trong phỏng vấn: "đây là kết quả thực tế"
     * Assertion cuối: NONE=oversell, còn lại=không oversell
     */
    @Test
    @DisplayName("COMPARISON: Chạy tất cả 4 mode — bảng so sánh before/after locking")
    void comparison_allLockModes_showDifference() {
        List<String> modes = List.of("NONE", "OPTIMISTIC", "PESSIMISTIC", "REDIS");
        List<SimulationRunResponse> results = new ArrayList<>();

        for (String mode : modes) {
            // Reset data giữa các lần chạy
            simulationRequestRepository.deleteAll();
            simulationRunRepository.deleteAll();
            orderRepository.deleteAll();

            Inventory inventory = inventoryRepository.findByProductId(testProduct.getId()).get();
            inventory.setStock(1);
            inventoryRepository.save(inventory);

            CreateSimulationRequest request = new CreateSimulationRequest();
            request.setProductId(testProduct.getId());
            request.setInitialStock(1);
            request.setConcurrentUsers(20);
            request.setLockMode(mode);

            SimulationRunResponse response = simulationService.runSimulation(request);
            results.add(response);
        }

        // In bảng so sánh
        System.out.println("\n");
        System.out.println("═══════════════════════════════════════════════════════════════════");
        System.out.println("          FLASH SALE CONCURRENCY — COMPARISON RESULTS              ");
        System.out.println("═══════════════════════════════════════════════════════════════════");
        System.out.printf("%-15s %-12s %-12s %-12s %-15s %-12s%n",
            "LockMode", "Success", "Failed", "FinalStock", "Overselling?", "AvgLatency");
        System.out.println("───────────────────────────────────────────────────────────────────");

        for (int i = 0; i < modes.size(); i++) {
            SimulationRunResponse r = results.get(i);
            String overselling = r.getOversellingDetected() ? "❌ YES" : "✅ NO";
            System.out.printf("%-15s %-12d %-12d %-12d %-15s %-12s ms%n",
                modes.get(i),
                r.getSuccessCount(),
                r.getFailedCount(),
                r.getFinalStock(),
                overselling,
                r.getAvgLatencyMs()
            );
        }
        System.out.println("═══════════════════════════════════════════════════════════════════\n");

        // Assertions
        // NONE: phải oversell
        assertThat(results.get(0).getOversellingDetected())
            .as("NONE mode PHẢI gây ra overselling (đây là baseline bug)")
            .isTrue();

        // OPTIMISTIC, PESSIMISTIC, REDIS: không được oversell
        for (int i = 1; i < results.size(); i++) {
            assertThat(results.get(i).getOversellingDetected())
                .as("%s mode PHẢI ngăn overselling", modes.get(i))
                .isFalse();

            assertThat(results.get(i).getSuccessCount())
                .as("%s mode: chỉ 1 người mua thành công khi stock=1", modes.get(i))
                .isEqualTo(1);
        }
    }
}
