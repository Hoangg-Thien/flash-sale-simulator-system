package com.hthien.flash_sale.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.hthien.flash_sale.entity.Product;

@Repository
public interface ProductRepository extends JpaRepository<Product, Long>{
    
}
