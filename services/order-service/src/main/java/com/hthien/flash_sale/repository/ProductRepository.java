package com.hthien.flash_sale.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.stereotype.Repository;

import com.hthien.flash_sale.entity.Product;

@Repository
public interface ProductRepository extends JpaRepository<Product, Long>{
    List<Product> findFirst16ByPendingInitialStockIsNotNullAndIdGreaterThanOrderByIdAsc(long cursor);

    @Transactional
    @Modifying
    @Query("update Product p set p.pendingInitialStock = null where p.id = :id and p.pendingInitialStock = :stock")
    int acknowledgeInitialization(long id, int stock);
    
}
