package com.hthien.flash_sale.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class CreateSimulationRequest {
    
    @NotNull(message = "Product ID is required")
    private Long productId;

    @NotNull(message = "Initial stock is required")
    @Min(value = 0, message = "Initial stock must be >= 0")
    private Integer initialStock;

    @NotNull(message = "Concurrent users is required")
    @Min(value = 1, message = "Concurrent users must be >= 1")
    @Max(value = 200, message = "Concurrent users must be <= 200")
    private Integer concurrentUsers;

    @NotNull(message = "Lock mode is required")
    private String lockMode;
}
