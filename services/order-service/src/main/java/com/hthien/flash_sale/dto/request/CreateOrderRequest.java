package com.hthien.flash_sale.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class CreateOrderRequest {
    
    @NotNull(message = "Product ID is required")
    private Long productId;

    @NotNull(message = "Quantity is required")
    @Min(value = 1, message = "Quantity must be >= 1")
    @Max(value = 100, message = "Quantity must be <= 100")
    private Integer quantity;

    @NotNull(message = "Lock mode is required")
    private String lockMode;
}
