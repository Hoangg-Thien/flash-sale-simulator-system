package com.hthien.flash_sale.controller;

import com.hthien.flash_sale.dto.request.ResetStockRequest;
import com.hthien.flash_sale.dto.response.ProductResponse;
import com.hthien.flash_sale.service.ProductService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;

@Profile("lab")
@RestController
@RequiredArgsConstructor
class LabStockController {
    private final ProductService products;

    @PostMapping("/api/v1/products/{id}/inventory/reset")
    ProductResponse reset(@PathVariable long id, @Valid @RequestBody ResetStockRequest request) {
        return products.resetStock(id, request);
    }
}
