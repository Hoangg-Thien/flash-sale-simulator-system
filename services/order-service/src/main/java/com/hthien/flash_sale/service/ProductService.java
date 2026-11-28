package com.hthien.flash_sale.service;

import com.hthien.flash_sale.client.StockClient;
import com.hthien.flash_sale.dto.request.CreateProductRequest;
import com.hthien.flash_sale.dto.request.ResetStockRequest;
import com.hthien.flash_sale.dto.response.ProductResponse;
import com.hthien.flash_sale.entity.Product;
import com.hthien.flash_sale.exception.ProductNotFoundException;
import com.hthien.flash_sale.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
@Slf4j
@RequiredArgsConstructor
public class ProductService {
    private final ProductRepository products;
    private final StockClient inventory;
    private long cursor;

    public ProductResponse createProduct(CreateProductRequest request) {
        Product product = products.save(Product.builder().name(request.getName()).price(request.getPrice())
            .pendingInitialStock(request.getInitialStock()).build());
        return provision(product);
    }

    public ProductResponse getProduct(Long id) {
        Product product = product(id);
        return product.getPendingInitialStock() == null
            ? ProductResponse.from(product, inventory.get(id)) : provision(product);
    }

    public ProductResponse resetStock(Long id, ResetStockRequest request) {
        Product product = product(id);
        if (product.getPendingInitialStock() != null) {
            initialize(product);
        }
        return ProductResponse.from(product, inventory.reset(id, request.getNewStock()));
    }

    @Scheduled(fixedDelayString = "${inventory.provision-delay-ms:5000}")
    public void resumeInitialization() {
        var batch = products.findFirst16ByPendingInitialStockIsNotNullAndIdGreaterThanOrderByIdAsc(cursor);
        for (Product product : batch) {
            provision(product);
            cursor = product.getId();
        }
        if (batch.size() < 16) {
            cursor = 0;
        }
    }

    private Product product(long id) {
        return products.findById(id).orElseThrow(() -> new ProductNotFoundException(id));
    }

    private ProductResponse provision(Product product) {
        try {
            return ProductResponse.from(product, initialize(product));
        } catch (ResponseStatusException e) {
            log.warn("Stock initialization pending for product {}: {}", product.getId(), e.getReason());
            return ProductResponse.from(product, null);
        }
    }

    private StockClient.Stock initialize(Product product) {
        int initial = product.getPendingInitialStock();
        StockClient.Stock stock = inventory.initialize(product.getId(), initial);
        products.acknowledgeInitialization(product.getId(), initial);
        return stock;
    }
}
