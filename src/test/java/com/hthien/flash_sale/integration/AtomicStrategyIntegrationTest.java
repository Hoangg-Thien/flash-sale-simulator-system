package com.hthien.flash_sale.integration;

import static org.assertj.core.api.Assertions.*;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.support.TransactionTemplate;

import com.hthien.flash_sale.concurrency.InventoryLockStrategy;
import com.hthien.flash_sale.dto.request.CreateOrderRequest;
import com.hthien.flash_sale.entity.Inventory;
import com.hthien.flash_sale.entity.Product;
import com.hthien.flash_sale.exception.InsufficientStockException;
import com.hthien.flash_sale.exception.ProductNotFoundException;
import com.hthien.flash_sale.service.OrderService;

import jakarta.persistence.EntityManager;

class AtomicStrategyIntegrationTest extends BaseIntegrationTest {

    @Autowired private OrderService orderService;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private Map<String, InventoryLockStrategy> strategies;
    @Autowired private EntityManager entityManager;
    @Autowired private JdbcTemplate jdbcTemplate;
    private Product product;
    private static final Instant OLD_TIMESTAMP = Instant.parse("2000-01-01T00:00:00Z");

    @BeforeEach
    void setUp() {
        cleanDatabase();
        product = productRepository.save(Product.builder().name("Atomic test").price(BigDecimal.TEN).build());
        inventoryRepository.save(Inventory.builder().product(product).stock(10).build());
        jdbcTemplate.update("UPDATE inventory SET update_at = ? WHERE product_id = ?",
            Timestamp.from(OLD_TIMESTAMP), product.getId());
    }

    @Test
    void atomicOrder_updatesStockVersionAndTimestamp() {
        var response = orderService.createOrder(request(3), null);
        assertThat(response.getLockMode()).isEqualTo("ATOMIC");
        assertThat(response.getItems().get(0).getQuantity()).isEqualTo(3);
        Inventory inventory = inventoryRepository.findByProductId(product.getId()).orElseThrow();
        assertThat(inventory.getStock()).isEqualTo(7);
        assertThat(inventory.getVersion()).isEqualTo(1);
        assertThat(inventory.getUpdatedAt()).isAfter(OLD_TIMESTAMP);
    }

    @Test
    void insufficientStock_leavesStockVersionAndTimestampUnchanged() {
        assertThatThrownBy(() -> orderService.createOrder(request(11), null))
            .isInstanceOf(InsufficientStockException.class);
        Inventory inventory = inventoryRepository.findByProductId(product.getId()).orElseThrow();
        assertThat(inventory.getStock()).isEqualTo(10);
        assertThat(inventory.getVersion()).isZero();
        assertThat(inventory.getUpdatedAt()).isEqualTo(OLD_TIMESTAMP);
        assertThat(orderRepository.count()).isZero();
    }

    @Test
    void missingInventory_isReportedAsProductNotFound() {
        inventoryRepository.deleteAll();
        assertThatThrownBy(() -> transactionTemplate.execute(status -> atomic().deductStock(product.getId(), 1)))
            .isInstanceOf(ProductNotFoundException.class);
        assertThatThrownBy(() -> transactionTemplate.execute(status -> atomic().deductStock(-1L, 1)))
            .isInstanceOf(ProductNotFoundException.class);
    }

    @Test
    void bulkUpdate_readsFreshInventoryAndPreservesPendingChanges() {
        transactionTemplate.executeWithoutResult(status -> {
            Inventory loaded = inventoryRepository.findByProductId(product.getId()).orElseThrow();
            Product managedProduct = productRepository.findById(product.getId()).orElseThrow();
            managedProduct.setName("Pending change survives bulk update");

            Inventory first = atomic().deductStock(product.getId(), 3);
            assertThat(first.getStock()).isEqualTo(7);
            assertThat(first.getVersion()).isEqualTo(1);
            assertThat(entityManager.find(Inventory.class, loaded.getId()).getStock()).isEqualTo(7);

            Inventory second = atomic().deductStock(product.getId(), 2);
            assertThat(second.getStock()).isEqualTo(5);
            assertThat(second.getVersion()).isEqualTo(2);
        });
        assertThat(inventoryRepository.findByProductId(product.getId()).orElseThrow().getStock()).isEqualTo(5);
        assertThat(productRepository.findById(product.getId()).orElseThrow().getName())
            .isEqualTo("Pending change survives bulk update");
    }

    @Test
    void atomicCommit_preventsStaleOptimisticOverwrite() {
        Inventory stale = inventoryRepository.findByProductId(product.getId()).orElseThrow();
        orderService.createOrder(request(3), null);
        stale.setStock(9);
        assertThatThrownBy(() -> inventoryRepository.saveAndFlush(stale))
            .isInstanceOf(ObjectOptimisticLockingFailureException.class);
        assertThat(inventoryRepository.findByProductId(product.getId()).orElseThrow().getStock()).isEqualTo(7);
        assertThat(orderRepository.count()).isEqualTo(1);
    }

    @ParameterizedTest
    @CsvSource({"1, 1", "10, 1", "10, 3", "0, 1", "10, 10"})
    void concurrentAtomicOrders_conserveStock(int initialStock, int quantity) throws Exception {
        runConcurrentOrders(initialStock, quantity);
    }

    @RepeatedTest(10)
    void concurrentAtomicOrders_stableAcrossRuns() throws Exception {
        runConcurrentOrders(10, 1);
    }

    private void runConcurrentOrders(int initialStock, int quantity) throws Exception {
        Inventory inventory = inventoryRepository.findByProductId(product.getId()).orElseThrow();
        inventory.setStock(initialStock);
        inventory = inventoryRepository.save(inventory);
        long initialVersion = inventory.getVersion();
        ExecutorService executor = Executors.newFixedThreadPool(20);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < 20; i++) {
                futures.add(executor.submit(() -> {
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Start gate timed out");
                    }
                    try {
                        orderService.createOrder(request(quantity), null);
                        return true;
                    } catch (InsufficientStockException expected) {
                        return false;
                    }
                }));
            }
            start.countDown();
            int successes = 0;
            for (Future<Boolean> future : futures) {
                if (future.get(20, TimeUnit.SECONDS)) {
                    successes++;
                }
            }
            assertThat(successes).isEqualTo(initialStock / quantity);
            Inventory remaining = inventoryRepository.findByProductId(product.getId()).orElseThrow();
            assertThat(remaining.getStock()).isEqualTo(initialStock - successes * quantity).isNotNegative();
            assertThat(remaining.getVersion()).isEqualTo(initialVersion + successes);
            assertThat(orderRepository.count()).isEqualTo(successes);
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private InventoryLockStrategy atomic() {
        InventoryLockStrategy strategy = strategies.get("atomicUpdateStrategy");
        assertThat(strategy).as("ATOMIC strategy must be registered").isNotNull();
        return strategy;
    }

    private CreateOrderRequest request(int quantity) {
        CreateOrderRequest request = new CreateOrderRequest();
        request.setProductId(product.getId());
        request.setQuantity(quantity);
        request.setLockMode("ATOMIC");
        return request;
    }
}
