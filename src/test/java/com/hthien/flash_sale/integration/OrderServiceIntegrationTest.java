package com.hthien.flash_sale.integration;

import static org.assertj.core.api.Assertions.*;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;

import com.hthien.flash_sale.dto.request.CreateOrderRequest;
import com.hthien.flash_sale.dto.response.OrderResponse;
import com.hthien.flash_sale.entity.Inventory;
import com.hthien.flash_sale.entity.Product;
import com.hthien.flash_sale.enums.OrderStatus;
import com.hthien.flash_sale.exception.DuplicateOrderException;
import com.hthien.flash_sale.exception.InsufficientStockException;
import com.hthien.flash_sale.exception.ProductNotFoundException;
import com.hthien.flash_sale.repository.InventoryRepository;
import com.hthien.flash_sale.repository.OrderRepository;
import com.hthien.flash_sale.repository.ProductRepository;
import com.hthien.flash_sale.service.OrderService;

class OrderServiceIntegrationTest extends BaseIntegrationTest {

    @Autowired private OrderService orderService;

    private Product testProduct;

    @BeforeEach
    void setUp() {
        cleanDatabase();

        testProduct = productRepository.save(
            Product.builder()
                .name("iPhone 15 Pro")
                .price(new BigDecimal("29990000"))
                .build()
        );

        inventoryRepository.save(
            Inventory.builder()
                .product(testProduct)
                .stock(5)
                .build()
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {"NONE", "OPTIMISTIC", "PESSIMISTIC", "REDIS", "ATOMIC"})
    @DisplayName("Create Order — with sufficient stock, should succeed and decrement inventory")
    void createOrder_withSufficientStock_shouldSucceed(String lockMode) {
        CreateOrderRequest request = buildRequest(1, lockMode);

        OrderResponse response = orderService.createOrder(request, null);

        assertThat(response.getOrderStatus()).isEqualTo(OrderStatus.SUCCESS);
        assertThat(response.getItems()).hasSize(1);
        assertThat(response.getItems().get(0).getQuantity()).isEqualTo(1);

        Inventory updated = inventoryRepository.findByProductId(testProduct.getId()).get();
        assertThat(updated.getStock()).isEqualTo(4);
    }

    @ParameterizedTest
    @ValueSource(strings = {"NONE", "OPTIMISTIC", "PESSIMISTIC", "REDIS", "ATOMIC"})
    @DisplayName("Create Order — with zero stock, should throw InsufficientStockException")
    void createOrder_withZeroStock_shouldThrow(String lockMode) {
        Inventory inventory = inventoryRepository.findByProductId(testProduct.getId()).get();
        inventory.setStock(0);
        inventoryRepository.save(inventory);

        CreateOrderRequest request = buildRequest(1, lockMode);

        assertThatThrownBy(() -> orderService.createOrder(request, null))
            .isInstanceOf(InsufficientStockException.class);

        assertThat(inventoryRepository.findByProductId(testProduct.getId()).get().getStock()).isEqualTo(0);
    }

    @ParameterizedTest
    @ValueSource(strings = {"NONE", "OPTIMISTIC", "PESSIMISTIC", "REDIS", "ATOMIC"})
    @DisplayName("Idempotency — duplicate requests with same idempotency key return same order without double decrement")
    void createOrder_withSameIdempotencyKey_shouldReturnSameOrder(String lockMode) {
        String key = "idem-key-" + UUID.randomUUID();
        CreateOrderRequest request = buildRequest(1, lockMode);

        OrderResponse first = orderService.createOrder(request, key);
        
        DuplicateOrderException ex = catchThrowableOfType(
            () -> orderService.createOrder(request, key),
            DuplicateOrderException.class
        );

        assertThat(ex.getExistingOrder().getId()).isEqualTo(first.getId());

        Inventory updated = inventoryRepository.findByProductId(testProduct.getId()).get();
        assertThat(updated.getStock()).isEqualTo(4);
    }

    @ParameterizedTest
    @ValueSource(strings = {"NONE", "OPTIMISTIC", "PESSIMISTIC", "REDIS", "ATOMIC"})
    @DisplayName("Create Order — non-existent product throws ProductNotFoundException")
    void createOrder_withNonExistentProduct_shouldThrow(String lockMode) {
        CreateOrderRequest request = new CreateOrderRequest();
        request.setProductId(999999L);
        request.setQuantity(1);
        request.setLockMode(lockMode);

        assertThatThrownBy(() -> orderService.createOrder(request, null))
            .isInstanceOf(ProductNotFoundException.class);
    }

    @Test
    @DisplayName("Create Order — invalid lockMode throws IllegalArgumentException")
    void createOrder_withInvalidLockMode_shouldThrow() {
        CreateOrderRequest request = buildRequest(1, "INVALID_MODE");

        assertThatThrownBy(() -> orderService.createOrder(request, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Invalid lockMode");
    }

    @ParameterizedTest
    @ValueSource(strings = {"NONE", "OPTIMISTIC", "PESSIMISTIC", "REDIS", "ATOMIC"})
    @DisplayName("Create Order — correctly snapshots unit price and calculates total price")
    void createOrder_shouldSnapshotPrice(String lockMode) {
        CreateOrderRequest request = buildRequest(2, lockMode);

        OrderResponse response = orderService.createOrder(request, null);

        assertThat(response.getItems().get(0).getUnitPrice()).isEqualByComparingTo("29990000");
        assertThat(response.getItems().get(0).getTotalPrice()).isEqualByComparingTo("59980000");
    }

    private CreateOrderRequest buildRequest(int quantity, String lockMode) {
        CreateOrderRequest request = new CreateOrderRequest();
        request.setProductId(testProduct.getId());
        request.setQuantity(quantity);
        request.setLockMode(lockMode);
        return request;
    }
}
