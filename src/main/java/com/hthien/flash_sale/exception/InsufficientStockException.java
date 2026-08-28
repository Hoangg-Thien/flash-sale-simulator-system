package com.hthien.flash_sale.exception;

public class InsufficientStockException extends RuntimeException{
    public InsufficientStockException(Long productId, int requested, int available) {
        super(String.format("Insufficient stock for product %d: requested=%d, available=%d",
        productId, requested, available));
    }
}
