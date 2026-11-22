package com.hthien.flash_sale.integration;

import static org.assertj.core.api.Assertions.*;

import java.math.BigDecimal;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.hthien.flash_sale.dto.request.CreateOrderRequest;
import com.hthien.flash_sale.dto.request.CreateSimulationRequest;
import com.hthien.flash_sale.dto.response.OrderResponse;
import com.hthien.flash_sale.dto.response.SimulationRunResponse;
import com.hthien.flash_sale.entity.Inventory;
import com.hthien.flash_sale.entity.Product;
import com.hthien.flash_sale.enums.OrderStatus;
import com.hthien.flash_sale.repository.InventoryRepository;
import com.hthien.flash_sale.repository.OrderRepository;
import com.hthien.flash_sale.repository.ProductRepository;
import com.hthien.flash_sale.repository.SimulationRequestRepository;
import com.hthien.flash_sale.repository.SimulationRunRepository;
import com.hthien.flash_sale.service.OrderService;
import com.hthien.flash_sale.service.SimulationService;

class ConcurrencyIntegrationTest extends BaseIntegrationTest {

    @Autowired private SimulationService simulationService;
    @Autowired private OrderService orderService;
    @Autowired private ProductRepository productRepository;
    @Autowired private InventoryRepository inventoryRepository;
    @Autowired private SimulationRunRepository simulationRunRepository;
    @Autowired private SimulationRequestRepository simulationRequestRepository;
    @Autowired private OrderRepository orderRepository;

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

    @Test
    @DisplayName("Case 6: Stock=10, 20 threads, PESSIMISTIC lock → exactly 10 success, 10 failed, finalStock=0")
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

    @Test
    @DisplayName("Case 6b: Stock=10, 20 threads, REDIS lock → exactly 10 success, 10 failed, finalStock=0")
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

    @Test
    @DisplayName("Case 7: Stock=0, 20 threads → 0 success, 20 failed, all InsufficientStockException")
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

    @Test
    @DisplayName("Case 12: Concurrent purchase on 2 different products → both succeed independently (Key isolation)")
    void concurrent_differentProducts_shouldNotBlockEachOther() throws Exception {
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

        startLatch.countDown();
        boolean completed = doneLatch.await(5, TimeUnit.SECONDS);

        executor.shutdown();

        assertThat(completed).isTrue();
        assertThat(successCount.get()).isEqualTo(2);

        Inventory invA = inventoryRepository.findByProductId(testProduct.getId()).get();
        Inventory invB = inventoryRepository.findByProductId(productB.getId()).get();
        assertThat(invA.getStock()).isEqualTo(9);
        assertThat(invB.getStock()).isEqualTo(0);
    }
}
