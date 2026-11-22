package com.hthien.flash_sale.integration;

import static org.assertj.core.api.Assertions.*;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.hthien.flash_sale.dto.request.CreateOrderRequest;
import com.hthien.flash_sale.dto.response.OrderResponse;
import com.hthien.flash_sale.entity.Inventory;
import com.hthien.flash_sale.entity.Product;
import com.hthien.flash_sale.exception.LockAcquisitionException;
import com.hthien.flash_sale.repository.InventoryRepository;
import com.hthien.flash_sale.repository.OrderRepository;
import com.hthien.flash_sale.repository.ProductRepository;
import com.hthien.flash_sale.repository.SimulationRequestRepository;
import com.hthien.flash_sale.repository.SimulationRunRepository;
import com.hthien.flash_sale.service.OrderService;

/**
 * Integration test verifying system behavior and fault-tolerance
 * when Redis goes down (simulated via redis.stop()).
 */
@SpringBootTest(properties = {
    "app.lock.wait-time-seconds=1"
})
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RedisFailureIntegrationTest {

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

    @Autowired
    private OrderService orderService;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private InventoryRepository inventoryRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private SimulationRunRepository simulationRunRepository;

    @Autowired
    private SimulationRequestRepository simulationRequestRepository;

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
                .name("Redis Outage Test Product")
                .price(BigDecimal.valueOf(250000))
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
    @DisplayName("Redis Outage — When Redis is stopped, REDIS lock mode fails gracefully with LockAcquisitionException and preserves DB stock")
    void whenRedisStops_redisLockFailsGracefullyWithoutCorruptingDatabase() {
        // 1. Verify normal REDIS lock works while Redis is up
        CreateOrderRequest request1 = new CreateOrderRequest();
        request1.setProductId(testProduct.getId());
        request1.setQuantity(2);
        request1.setLockMode("REDIS");

        OrderResponse order1 = orderService.createOrder(request1, UUID.randomUUID().toString());
        assertThat(order1).isNotNull();
        assertThat(inventoryRepository.findByProductId(testProduct.getId()).orElseThrow().getStock()).isEqualTo(8);

        // 2. Stop the Redis container to simulate network partition or Redis server crash
        redis.stop();

        // 3. Attempt to place an order with REDIS lock mode
        CreateOrderRequest request2 = new CreateOrderRequest();
        request2.setProductId(testProduct.getId());
        request2.setQuantity(2);
        request2.setLockMode("REDIS");

        // Must fail with LockAcquisitionException (no hang, no crash, no partial commit)
        assertThatThrownBy(() -> orderService.createOrder(request2, UUID.randomUUID().toString()))
            .isInstanceOf(LockAcquisitionException.class);

        // 4. Verify PostgreSQL stock remained untouched at 8 (no dirty decrement)
        Inventory finalInventory = inventoryRepository.findByProductId(testProduct.getId()).orElseThrow();
        assertThat(finalInventory.getStock())
            .as("Stock must remain exactly 8 despite Redis outage")
            .isEqualTo(8);

        // 5. Database-only lock modes (PESSIMISTIC, OPTIMISTIC) still work even when Redis is down
        CreateOrderRequest pessimisticRequest = new CreateOrderRequest();
        pessimisticRequest.setProductId(testProduct.getId());
        pessimisticRequest.setQuantity(3);
        pessimisticRequest.setLockMode("PESSIMISTIC");

        OrderResponse pessimisticOrder = orderService.createOrder(pessimisticRequest, UUID.randomUUID().toString());
        assertThat(pessimisticOrder).isNotNull();

        assertThat(inventoryRepository.findByProductId(testProduct.getId()).orElseThrow().getStock())
            .as("Pessimistic lock works directly on PostgreSQL without Redis dependency")
            .isEqualTo(5);

        CreateOrderRequest atomicRequest = new CreateOrderRequest();
        atomicRequest.setProductId(testProduct.getId());
        atomicRequest.setQuantity(1);
        atomicRequest.setLockMode("ATOMIC");
        assertThat(orderService.createOrder(atomicRequest, UUID.randomUUID().toString())).isNotNull();
        assertThat(inventoryRepository.findByProductId(testProduct.getId()).orElseThrow().getStock())
            .as("ATOMIC works without Redis after an outage")
            .isEqualTo(4);
    }
}
