package com.hthien.flash_sale.controller;

import com.hthien.flash_sale.dto.request.CreateOrderRequest;
import com.hthien.flash_sale.dto.response.OrderResponse;
import com.hthien.flash_sale.service.OrderService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/orders")
@RequiredArgsConstructor
public class OrderController {
    private final OrderService orders;

    @PostMapping
    public ResponseEntity<OrderResponse> create(@Valid @RequestBody CreateOrderRequest request,
            @RequestHeader(value="Idempotency-Key", required=false) String key) {
        var result = orders.purchase(request, key);
        int status = switch (result.order().getOrderStatus()) {
            case CONFIRMED -> result.created() ? 201 : 200;
            case REJECTED -> 409;
            case PENDING -> 202;
        };
        return ResponseEntity.status(status).body(result.order());
    }

    @GetMapping("/{id}")
    public OrderResponse get(@PathVariable long id) { return orders.get(id); }
}
