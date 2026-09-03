package com.hthien.flash_sale.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
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

    @org.springframework.data.jpa.repository.Modifying
    @Query(value = "UPDATE inventory SET stock = :newStock, update_at = CURRENT_TIMESTAMP WHERE product_id = :productId", nativeQuery = true)
    void updateStockUnsafe(@Param("productId") Long productId, @Param("newStock") Integer newStock);
}
