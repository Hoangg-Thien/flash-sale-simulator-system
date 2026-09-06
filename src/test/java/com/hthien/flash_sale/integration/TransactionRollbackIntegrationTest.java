package com.hthien.flash_sale.integration;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.dao.DataIntegrityViolationException;

import com.hthien.flash_sale.dto.request.CreateOrderRequest;
import com.hthien.flash_sale.entity.Inventory;
import com.hthien.flash_sale.entity.Order;
import com.hthien.flash_sale.entity.Product;
import com.hthien.flash_sale.repository.InventoryRepository;
import com.hthien.flash_sale.repository.OrderRepository;
import com.hthien.flash_sale.repository.ProductRepository;
import com.hthien.flash_sale.service.OrderService;

/**
 * Integration test verifying that database transactions roll back completely
 * when an exception is injected into the order pipeline.
 */
class TransactionRollbackIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private OrderService orderService;

    @SpyBean
    private OrderRepository orderRepository;

    private Product testProduct;

    @BeforeEach
    void setUp() {
        cleanDatabase();

        testProduct = productRepository.save(
            Product.builder()
                .name("Rollback Test Product")
                .price(BigDecimal.valueOf(100000))
                .build()
        );

        inventoryRepository.save(
            Inventory.builder()
                .product(testProduct)
                .stock(10)
                .build()
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {"NONE", "OPTIMISTIC", "PESSIMISTIC", "REDIS"})
    @DisplayName("Transaction Rollback — When OrderRepository throws exception, inventory stock decrement must roll back")
    void whenOrderSaveFails_transactionMustRollbackInventory(String lockMode) {
        // Arrange: Inject RuntimeException when saving the order entity
        doThrow(new RuntimeException("Simulated Database I/O Failure during order save"))
            .when(orderRepository).save(any(Order.class));

        CreateOrderRequest request = new CreateOrderRequest();
        request.setProductId(testProduct.getId());
        request.setQuantity(2);
        request.setLockMode(lockMode);

        // Act & Assert: Order creation must fail with an exception
        assertThatThrownBy(() -> orderService.createOrder(request, UUID.randomUUID().toString()))
            .isInstanceOf(RuntimeException.class);

        // Verify: Inventory stock in PostgreSQL was NOT decremented (remains 10)
        Inventory inventoryAfterRollback = inventoryRepository.findByProductId(testProduct.getId()).orElseThrow();
        assertThat(inventoryAfterRollback.getStock())
            .as("Stock should remain 10 because transaction rolled back")
            .isEqualTo(10);

        // Verify: No order was committed to PostgreSQL
        assertThat(orderRepository.count()).as("Orders count must be 0").isEqualTo(0);
    }

    @Test
    @DisplayName("Transaction Rollback — Duplicate idempotency key rollback prevents duplicate inventory decrement")
    void whenDuplicateIdempotencyKey_transactionRollsBack() {
        // Create 1st order successfully
        String idempotencyKey = UUID.randomUUID().toString();
        CreateOrderRequest request = new CreateOrderRequest();
        request.setProductId(testProduct.getId());
        request.setQuantity(2);
        request.setLockMode("REDIS");

        orderService.createOrder(request, idempotencyKey);

        Inventory inventoryAfter1st = inventoryRepository.findByProductId(testProduct.getId()).orElseThrow();
        assertThat(inventoryAfter1st.getStock()).isEqualTo(8);

        // Inject DataIntegrityViolationException on save to simulate concurrent duplicate insert
        doThrow(new DataIntegrityViolationException("Duplicate key violation"))
            .when(orderRepository).save(any(Order.class));

        // Act: Attempt to place duplicate order
        assertThatThrownBy(() -> orderService.createOrder(request, idempotencyKey))
            .isNotNull();

        // Verify: Stock was NOT decremented again (still 8, not 6)
        Inventory inventoryAfterDuplicate = inventoryRepository.findByProductId(testProduct.getId()).orElseThrow();
        assertThat(inventoryAfterDuplicate.getStock()).isEqualTo(8);
        assertThat(orderRepository.count()).isEqualTo(1);
    }
}
