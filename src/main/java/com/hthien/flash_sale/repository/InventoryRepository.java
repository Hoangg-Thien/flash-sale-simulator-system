package com.hthien.flash_sale.repository;

import java.util.Optional;
import java.time.Instant;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.hthien.flash_sale.entity.Inventory;

import jakarta.persistence.LockModeType;


@Repository
public interface InventoryRepository extends JpaRepository<Inventory, Long>{
    Optional<Inventory> findByProductId(Long productId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT i FROM Inventory i WHERE i.product.id = :productId")
    Optional<Inventory> findByProductIdForUpdate(@Param("productId") Long productId);

    // Flush before clearing so pending changes survive the bulk update.
    // https://docs.spring.io/spring-data/jpa/docs/3.5.x/api/org/springframework/data/jpa/repository/Modifying.html
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
        UPDATE inventory
        SET stock = stock - :quantity, version = version + 1, update_at = :updatedAt
        WHERE product_id = :productId AND stock >= :quantity
        """, nativeQuery = true)
    int deductStockAtomic(@Param("productId") Long productId, @Param("quantity") int quantity,
        @Param("updatedAt") Instant updatedAt);

    @org.springframework.data.jpa.repository.Modifying
    @Query(value = "UPDATE inventory SET stock = :newStock, update_at = CURRENT_TIMESTAMP WHERE product_id = :productId", nativeQuery = true)
    void updateStockUnsafe(@Param("productId") Long productId, @Param("newStock") Integer newStock);
}
