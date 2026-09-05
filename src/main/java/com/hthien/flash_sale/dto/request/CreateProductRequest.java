package com.hthien.flash_sale.dto.request;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CreateProductRequest {
    
    @NotBlank(message = "Product name is required")
    @Size(max = 255)
    private String name;

    @NotNull(message = "Price is required")
    @DecimalMin(value = "0.0", inclusive = true, message = "Price must be >= 0")
    @Digits(integer = 10, fraction = 2)
    private BigDecimal price;

    @NotNull(message = "Initial stock is required")
    @Min(value = 0, message = "Initial stock must be >= 0")
    private Integer initialStock;
}
