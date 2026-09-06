package com.hthien.flash_sale.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

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

class SimulationServiceIntegrationTest extends BaseIntegrationTest {

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

    @Test
    @DisplayName("[MUST PASS] stock=1, 20 threads, mode=NONE → overselling occurs (finalStock<0 OR successCount>1)")
    void runSimulation_withNoLock_shouldDetectOverselling() {
        CreateSimulationRequest request = buildSimRequest(
            testProduct.getId(), 1, 20, "NONE");

        SimulationRunResponse response = simulationService.runSimulation(request);

        boolean oversellDetected =
            response.getFinalStock() < 0 ||
            response.getSuccessCount() > response.getInitialStock();

        assertThat(oversellDetected)
            .as("Race condition should occur with mode=NONE")
            .isTrue();

        assertThat(response.getSuccessCount() + response.getFailedCount()).isEqualTo(20);
        assertThat(response.getRequests()).hasSize(20);
    }

    @Test
    @DisplayName("[MUST PASS] Repeated 3 times — race condition occurs consistently with NONE lock")
    void runSimulation_withNoLock_reproducedAtLeastOnce_inThreeRuns() {
        int oversellCount = 0;

        for (int i = 0; i < 3; i++) {
            simulationRequestRepository.deleteAll();
            simulationRunRepository.deleteAll();
            orderRepository.deleteAll();

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

        assertThat(oversellCount).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("stock=10, 20 threads, mode=NONE → stats calculated properly")
    void runSimulation_stats_shouldBeCalculatedCorrectly() {
        Inventory inventory = inventoryRepository.findByProductId(testProduct.getId()).get();
        inventory.setStock(10);
        inventoryRepository.save(inventory);

        CreateSimulationRequest request = buildSimRequest(
            testProduct.getId(), 10, 20, "NONE");

        SimulationRunResponse response = simulationService.runSimulation(request);

        assertThat(response.getSuccessCount() + response.getFailedCount()).isEqualTo(20);
        assertThat(response.getRequests()).hasSize(20);
        assertThat(response.getAvgLatencyMs()).isGreaterThan(BigDecimal.ZERO);
        assertThat(response.getThroughputRps()).isGreaterThan(BigDecimal.ZERO);
        assertThat(response.getExecutionTimeMs()).isGreaterThan(0L);

        assertThat(simulationRunRepository.findById(response.getSimulationRunId())).isPresent();
        assertThat(simulationRequestRepository.findBySimulationRunId(response.getSimulationRunId())).hasSize(20);
    }

    @Test
    @DisplayName("[MUST PASS] OPTIMISTIC LOCK: stock=1, 20 threads → oversellingDetected=false")
    void runSimulation_withOptimisticLock_shouldPreventOverselling() {
        CreateSimulationRequest request = buildSimRequest(
            testProduct.getId(), 1, 20, "OPTIMISTIC");

        SimulationRunResponse response = simulationService.runSimulation(request);

        assertThat(response.getOversellingDetected()).isFalse();
        assertThat(response.getSuccessCount()).isEqualTo(1);
        assertThat(response.getFinalStock()).isEqualTo(0);
        assertThat(response.getSuccessCount() + response.getFailedCount()).isEqualTo(20);
        assertThat(response.getFailedCount()).isEqualTo(19);
    }

    @Test
    @DisplayName("[STABILITY] OPTIMISTIC LOCK: stable across multiple runs")
    void runSimulation_withOptimisticLock_stableAcrossMultipleRuns() {
        for (int i = 0; i < 3; i++) {
            simulationRequestRepository.deleteAll();
            simulationRunRepository.deleteAll();
            orderRepository.deleteAll();

            Inventory inventory = inventoryRepository.findByProductId(testProduct.getId()).get();
            inventory.setStock(1);
            inventoryRepository.save(inventory);

            CreateSimulationRequest request = buildSimRequest(
                testProduct.getId(), 1, 20, "OPTIMISTIC");
            SimulationRunResponse response = simulationService.runSimulation(request);

            assertThat(response.getOversellingDetected()).isFalse();
            assertThat(response.getSuccessCount()).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("[MUST PASS] PESSIMISTIC LOCK: stock=1, 20 threads → oversellingDetected=false")
    void runSimulation_withPessimisticLock_shouldPreventOverselling() {
        CreateSimulationRequest request = buildSimRequest(
            testProduct.getId(), 1, 20, "PESSIMISTIC");

        SimulationRunResponse response = simulationService.runSimulation(request);

        assertThat(response.getOversellingDetected()).isFalse();
        assertThat(response.getSuccessCount()).isEqualTo(1);
        assertThat(response.getFinalStock()).isEqualTo(0);
        assertThat(response.getSuccessCount() + response.getFailedCount()).isEqualTo(20);
    }

    @Test
    @DisplayName("[STABILITY] PESSIMISTIC LOCK: stable across multiple runs")
    void runSimulation_withPessimisticLock_stableAcrossMultipleRuns() {
        for (int i = 0; i < 3; i++) {
            simulationRequestRepository.deleteAll();
            simulationRunRepository.deleteAll();
            orderRepository.deleteAll();

            Inventory inventory = inventoryRepository.findByProductId(testProduct.getId()).get();
            inventory.setStock(1);
            inventoryRepository.save(inventory);

            CreateSimulationRequest request = buildSimRequest(
                testProduct.getId(), 1, 20, "PESSIMISTIC");
            SimulationRunResponse response = simulationService.runSimulation(request);

            assertThat(response.getOversellingDetected()).isFalse();
            assertThat(response.getSuccessCount()).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("[MUST PASS] REDIS LOCK: stock=1, 20 threads → oversellingDetected=false")
    void runSimulation_withRedisLock_shouldPreventOverselling() {
        CreateSimulationRequest request = buildSimRequest(
            testProduct.getId(), 1, 20, "REDIS");

        SimulationRunResponse response = simulationService.runSimulation(request);

        assertThat(response.getOversellingDetected()).isFalse();
        assertThat(response.getSuccessCount()).isEqualTo(1);
        assertThat(response.getFinalStock()).isEqualTo(0);
        assertThat(response.getSuccessCount() + response.getFailedCount()).isEqualTo(20);
    }

    @Test
    @DisplayName("[STABILITY] REDIS LOCK: stable across multiple runs")
    void runSimulation_withRedisLock_stableAcrossMultipleRuns() {
        for (int i = 0; i < 3; i++) {
            simulationRequestRepository.deleteAll();
            simulationRunRepository.deleteAll();
            orderRepository.deleteAll();

            Inventory inventory = inventoryRepository.findByProductId(testProduct.getId()).get();
            inventory.setStock(1);
            inventoryRepository.save(inventory);

            CreateSimulationRequest request = buildSimRequest(
                testProduct.getId(), 1, 20, "REDIS");
            SimulationRunResponse response = simulationService.runSimulation(request);

            assertThat(response.getOversellingDetected()).isFalse();
            assertThat(response.getSuccessCount()).isEqualTo(1);
        }
    }

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
