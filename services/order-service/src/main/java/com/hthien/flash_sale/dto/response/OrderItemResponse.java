package com.hthien.flash_sale.dto.response;

import java.math.BigDecimal;

import com.hthien.flash_sale.entity.OrderItem;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class OrderItemResponse {
    private Long id;
    private Long productId;
    private String productName;
    private Integer quantity;
    private BigDecimal unitPrice;
    private BigDecimal totalPrice;

    public static OrderItemResponse from(OrderItem item){
        return OrderItemResponse.builder()
        .id(item.getId())
        .productId(item.getProduct().getId())
        .productName(item.getProduct().getName())
        .quantity(item.getQuantity())
        .unitPrice(item.getUnitPrice())
        .totalPrice(item.getUnitPrice().multiply(BigDecimal.valueOf(item.getQuantity())))
        .build();
    }
}
