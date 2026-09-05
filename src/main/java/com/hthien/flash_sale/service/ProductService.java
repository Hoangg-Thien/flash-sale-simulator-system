package com.hthien.flash_sale.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.hthien.flash_sale.dto.request.CreateProductRequest;
import com.hthien.flash_sale.dto.request.ResetStockRequest;
import com.hthien.flash_sale.dto.response.ProductRespone;
import com.hthien.flash_sale.entity.Inventory;
import com.hthien.flash_sale.entity.Product;
import com.hthien.flash_sale.exception.ProductNotFoundException;
import com.hthien.flash_sale.repository.InventoryRepository;
import com.hthien.flash_sale.repository.ProductRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
@RequiredArgsConstructor
public class ProductService {

    private final ProductRepository productRepository;
    private final InventoryRepository inventoryRepository;

    @Transactional
    public ProductRespone createProduct(CreateProductRequest request){

        log.info("Creating product: name={}, price={}, initialStock={}",
        request.getName(), request.getPrice(), request.getInitialStock());

        Product product = Product.builder()
        .name(request.getName())
        .price(request.getPrice())
        .build();
        product = productRepository.save(product);

        Inventory inventory = Inventory.builder()
        .product(product)
        .stock(request.getInitialStock())
        .build();
        inventory = inventoryRepository.save(inventory);

        log.info("Product created: id={}", product.getId());
        return ProductRespone.from(product, inventory);
    }

    @Transactional(readOnly = true)
    public ProductRespone getProduct(Long productId){

        Product product = productRepository.findById(productId)
        .orElseThrow(() -> new ProductNotFoundException(productId));
            
        Inventory inventory = inventoryRepository.findByProductId(productId)
        .orElseThrow(() -> new ProductNotFoundException(productId));

        return ProductRespone.from(product, inventory);
    }

    @Transactional
    public ProductRespone resetStock(Long productId, ResetStockRequest request){

        log.info("Resseting stock: productId={}, newStock={}", productId, request.getNewStock());

        Product product = productRepository.findById(productId)
        .orElseThrow(() -> new ProductNotFoundException(productId));

        Inventory inventory = inventoryRepository.findByProductId(productId)
        .orElseThrow(() -> new ProductNotFoundException(productId));

        inventory.setStock(request.getNewStock());
        inventoryRepository.save(inventory);

        log.info("Resseting stock: productId={}, newStock={}", productId, request.getNewStock());
        return ProductRespone.from(product, inventory);
    }
}
