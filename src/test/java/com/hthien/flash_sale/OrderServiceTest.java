package com.hthien.flash_sale;

import static org.assertj.core.api.Assertions.*;

import java.math.BigDecimal;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.*;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

import com.hthien.flash_sale.dto.request.CreateOrderRequest;
import com.hthien.flash_sale.dto.response.OrderResponse;
import com.hthien.flash_sale.entity.*;
import com.hthien.flash_sale.enums.OrderStatus;
import com.hthien.flash_sale.exception.*;
import com.hthien.flash_sale.repository.*;
import com.hthien.flash_sale.service.*;

@SpringBootTest
@Testcontainers
class OrderServiceTest {

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
        registry.add("spring.flyway.enabled", () -> "false");
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", redis::getFirstMappedPort);
    }

    @Autowired OrderService orderService;
    @Autowired ProductRepository productRepository;
    @Autowired InventoryRepository inventoryRepository;
    @Autowired OrderRepository orderRepository;

    private Product testProduct;

    @BeforeEach
    void setUp() {
        // Clear data giữa các test — tránh test phụ thuộc nhau
        orderRepository.deleteAll();
        inventoryRepository.deleteAll();
        productRepository.deleteAll();

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

    // TEST 1: Mua thành công
    @Test
    @DisplayName("Mua hàng thành công — stock đủ, giảm đúng số lượng")
    void createOrder_withSufficientStock_shouldSucceed() {
        CreateOrderRequest request = buildRequest(1, "NONE");

        OrderResponse response = orderService.createOrder(request, null);

        assertThat(response.getOrderStatus()).isEqualTo(OrderStatus.SUCCESS);
        assertThat(response.getItems()).hasSize(1);
        assertThat(response.getItems().get(0).getQuantity()).isEqualTo(1);

        // Verify stock thực sự giảm trong DB
        Inventory updated = inventoryRepository.findByProductId(testProduct.getId()).get();
        assertThat(updated.getStock()).isEqualTo(4); // 5 - 1 = 4
    }

    // TEST 2: Hết hàng
    @Test
    @DisplayName("Mua hàng thất bại — stock = 0, ném InsufficientStockException")
    void createOrder_withZeroStock_shouldThrow() {
        // Reset stock về 0
        Inventory inventory = inventoryRepository.findByProductId(testProduct.getId()).get();
        inventory.setStock(0);
        inventoryRepository.save(inventory);

        CreateOrderRequest request = buildRequest(1, "NONE");

        assertThatThrownBy(() -> orderService.createOrder(request, null))
            .isInstanceOf(InsufficientStockException.class);

        // Stock không thay đổi
        assertThat(inventoryRepository.findByProductId(testProduct.getId())
            .get().getStock()).isEqualTo(0);
    }

    // TEST 3: Idempotency — 2 request cùng key → 1 order, stock trừ 1 lần
    @Test
    @DisplayName("Idempotency — 2 request cùng key → chỉ tạo 1 order, stock chỉ trừ 1 lần")
    void createOrder_withSameIdempotencyKey_shouldReturnSameOrder() {
        String key = "idem-key-" + java.util.UUID.randomUUID();
        CreateOrderRequest request = buildRequest(1, "NONE");

        OrderResponse first = orderService.createOrder(request, key);
        
        DuplicateOrderException ex = catchThrowableOfType(
            () -> orderService.createOrder(request, key),
            DuplicateOrderException.class
        );

        // Cùng order ID
        assertThat(ex.getExistingOrder().getId()).isEqualTo(first.getId());

        // Stock chỉ trừ 1 lần dù gọi 2 lần
        Inventory updated = inventoryRepository.findByProductId(testProduct.getId()).get();
        assertThat(updated.getStock()).isEqualTo(4); // 5 - 1, không phải 3
    }

    // TEST 4: Product không tồn tại
    @Test
    @DisplayName("Product không tồn tại → ProductNotFoundException")
    void createOrder_withNonExistentProduct_shouldThrow() {
        CreateOrderRequest request = new CreateOrderRequest();
        request.setProductId(999999L);
        request.setQuantity(1);
        request.setLockMode("NONE");

        assertThatThrownBy(() -> orderService.createOrder(request, null))
            .isInstanceOf(ProductNotFoundException.class);
    }

    // TEST 5: LockMode không hợp lệ
    @Test
    @DisplayName("LockMode không hợp lệ → IllegalArgumentException")
    void createOrder_withInvalidLockMode_shouldThrow() {
        CreateOrderRequest request = buildRequest(1, "INVALID_MODE");

        assertThatThrownBy(() -> orderService.createOrder(request, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Invalid lockMode");
    }

    // TEST 6: Snapshot price
    @Test
    @DisplayName("OrderItem lưu snapshot price tại thời điểm mua")
    void createOrder_shouldSnapshotPrice() {
        CreateOrderRequest request = buildRequest(2, "NONE");

        OrderResponse response = orderService.createOrder(request, null);

        assertThat(response.getItems().get(0).getUnitPrice())
            .isEqualByComparingTo("29990000");
        assertThat(response.getItems().get(0).getTotalPrice())
            .isEqualByComparingTo("59980000"); // 29990000 * 2
    }

    // Helper
    private CreateOrderRequest buildRequest(int quantity, String lockMode) {
        CreateOrderRequest request = new CreateOrderRequest();
        request.setProductId(testProduct.getId());
        request.setQuantity(quantity);
        request.setLockMode(lockMode);
        return request;
    }
}