package com.hthien.flash_sale.controller;

import com.hthien.flash_sale.dto.request.CreateProductRequest;
import com.hthien.flash_sale.dto.response.ProductResponse;
import com.hthien.flash_sale.service.ProductService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/products")
@RequiredArgsConstructor
public class ProductController {

    private final ProductService productService;

    @PostMapping
    @Operation(summary = "Create a new product with initial inventory")
    public ResponseEntity<ProductResponse> createProduct(
        @Valid @RequestBody CreateProductRequest request
    ){
        return respond(productService.createProduct(request), HttpStatus.CREATED);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get product with current stock")
    public ResponseEntity<ProductResponse> getProduct(
        @PathVariable Long id
    ){
        return respond(productService.getProduct(id), HttpStatus.OK);
    }

    private ResponseEntity<ProductResponse> respond(ProductResponse response, HttpStatus ready) {
        return ResponseEntity.status(response.getStock() == null ? HttpStatus.ACCEPTED : ready).body(response);
    }
}
