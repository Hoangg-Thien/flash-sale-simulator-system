package com.hthien.flash_sale.exception;

import com.hthien.flash_sale.entity.Order;

import lombok.Getter;

/*Khác exception thông thường: mang theo Order đã tồn tại
để handler có thể trả lại response của order cũ (không chỉ trả lỗi)
*/

@Getter
public class DuplicateOrderException extends RuntimeException {

    private final Order existingOrder;

    public DuplicateOrderException(Order existingOrder) {
        super("Duplicate request: idempotency key already processed");
        this.existingOrder = existingOrder;
    }
}
