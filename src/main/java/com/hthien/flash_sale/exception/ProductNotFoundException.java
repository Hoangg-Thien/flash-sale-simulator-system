package com.hthien.flash_sale.exception;

public class ProductNotFoundException extends RuntimeException {
    public ProductNotFoundException(Long productId) {
        super("Không tìm thấy sản phẩm với ID: " + productId);
    }
}
