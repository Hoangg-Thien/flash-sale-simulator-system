package com.hthien.flash_sale.exception;

/**
 * Ném khi không acquire được Redis distributed lock trong thời gian chờ.
 * Phân biệt với InsufficientStockException:
 * - LockAcquisitionException: sản phẩm CÓ THỂ còn hàng, nhưng đang được xử lý bởi request khác
 * - InsufficientStockException: sản phẩm thật sự hết hàng
*/
public class LockAcquisitionException extends RuntimeException{
    public LockAcquisitionException(Long productId){
        super("Could not acquire lock for product " + productId +
        ". Product is being processed by another request. Please retry.");
    }
}
