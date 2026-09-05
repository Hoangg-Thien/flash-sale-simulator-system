package com.hthien.flash_sale.dto.response;

import java.math.BigDecimal;
import java.time.Instant;

import com.hthien.flash_sale.entity.Inventory;
import com.hthien.flash_sale.entity.Product;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ProductResponse {
    private Long id;
    private String name;
    private BigDecimal price;
    private Integer stock;
    private Long inventoryVersion;
    private Instant createdAt;
    private Instant updatedAt;

    public static ProductResponse from(Product product, Inventory inventory){
        return ProductResponse.builder()
        .id(product.getId())
        .name(product.getName())
        .price(product.getPrice())
        .stock(inventory.getStock())
        .inventoryVersion(inventory.getVersion())
        .createdAt(product.getCreatedAt())
        .updatedAt(product.getUpdatedAt())
        .build();
    }
}
