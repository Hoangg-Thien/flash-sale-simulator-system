package com.hthien.flash_sale.dto.response;

import java.time.Instant;
import java.util.List;

import com.hthien.flash_sale.entity.Order;
import com.hthien.flash_sale.enums.OrderStatus;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class OrderResponse {
    private Long id;
    private Long productId;
    private String productName;
    private OrderStatus orderStatus;
    private String lockMode;
    private String idempotencyKey;
    private Instant requestedAt;
    private Instant completedAt;
    private Long latencyMs;
    private String errorMessage;
    private List<OrderItemResponse> items;

    public static OrderResponse from(Order order){
        
        List<OrderItemResponse> itemResponses = order.getItems().stream()
        .map(OrderItemResponse::from)
        .toList();

        return OrderResponse.builder()
        .id(order.getId())
        .productId(order.getProduct().getId())
        .productName(order.getProduct().getName())
        .orderStatus(order.getOrderStatus())
        .lockMode(order.getLockMode().name())
        .idempotencyKey(order.getIdempotencyKey())
        .requestedAt(order.getRequestedAt())
        .completedAt(order.getCompletedAt())
        .latencyMs(order.getLatencyMs())
        .errorMessage(order.getErrorMessage())
        .items(itemResponses)
        .build();
    }
}
