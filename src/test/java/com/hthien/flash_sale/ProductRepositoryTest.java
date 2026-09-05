package com.hthien.flash_sale;

import java.math.BigDecimal;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.hthien.flash_sale.entity.Inventory;
import com.hthien.flash_sale.entity.Product;
import com.hthien.flash_sale.repository.InventoryRepository;
import com.hthien.flash_sale.repository.ProductRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class ProductRepositoryTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.9")
            .withDatabaseName("testdb")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
    }

    @Autowired ProductRepository productRepository;
    @Autowired InventoryRepository inventoryRepository;

    @Test
    @DisplayName("Tạo product và inventory — verify lưu đúng vào DB")
    void createProductAndInventory_shouldPersistCorrectly() {
        // Arrange
        Product product = Product.builder()
            .name("iPhone 15 Pro")
            .price(new BigDecimal("29990000"))
            .build();
        product = productRepository.save(product);

        Inventory inventory = Inventory.builder()
            .product(product)
            .stock(1)
            .build();
        inventory = inventoryRepository.save(inventory);

        // Act
        Optional<Inventory> found = inventoryRepository.findByProductId(product.getId());

        // Assert
        assertThat(found).isPresent();
        assertThat(found.get().getStock()).isEqualTo(1);
        assertThat(found.get().getVersion()).isEqualTo(0L);
    }

    @Test
    @DisplayName("UNIQUE constraint trên product_id — không thể tạo 2 inventory cho 1 product")
    void createDuplicateInventory_shouldThrowConstraintViolation() {
        // Arrange
        Product product = productRepository.save(
            Product.builder().name("Test Product").price(BigDecimal.TEN).build()
        );

        inventoryRepository.save(
            Inventory.builder().product(product).stock(10).build()
        );

        // Act & Assert
        assertThatThrownBy(() -> {
            inventoryRepository.save(
                Inventory.builder().product(product).stock(5).build()
            );
            inventoryRepository.flush();  // force flush để trigger constraint
        }).isInstanceOf(DataIntegrityViolationException.class);
    }
}
