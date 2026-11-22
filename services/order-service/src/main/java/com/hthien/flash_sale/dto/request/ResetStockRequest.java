package com.hthien.flash_sale.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class ResetStockRequest {
    @NotNull(message = "New stock is required")
    @Min(value = 0, message = "Stock must be >= 0")
    private Integer newStock;
}
