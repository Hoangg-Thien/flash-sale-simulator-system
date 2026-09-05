package com.hthien.flash_sale.controller;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.hthien.flash_sale.dto.request.CreateProductRequest;
import com.hthien.flash_sale.dto.request.ResetStockRequest;
import com.hthien.flash_sale.dto.response.ProductResponse;
import com.hthien.flash_sale.service.ProductService;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;



@RestController
@RequestMapping("/api/v1/products")
@RequiredArgsConstructor
public class ProductController {

    private final ProductService productService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a new product with initial inventory")
    public ProductResponse createProduct(
        @Valid @RequestBody CreateProductRequest request
    ){
        return productService.createProduct(request);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get product with current stock")
    public ProductResponse getProduct(
        @PathVariable Long id
    ){
        return productService.getProduct(id);
    }

    @PostMapping("/{id}/inventory/reset")
    @Operation(summary = "Reset product stock (for re-running simulations)")
    public ProductResponse resetStock(
        @PathVariable Long id,
        @Valid @RequestBody ResetStockRequest request
    ){
        return productService.resetStock(id, request);
    }
}
