package com.hthien.flash_sale.concurrency;

import java.util.function.Supplier;

import com.hthien.flash_sale.entity.Inventory;

public interface InventoryLockStrategy {

    /**
    * Thực hiện load kho, kiểm tra và trừ tồn kho theo chiến lược concurrency tương ứng.
    *
    * @param productId ID sản phẩm
    * @param quantity  Số lượng muốn mua
    * @return Inventory sau khi đã trừ kho thành công
    */
    // Runs inside the transaction supplied to around().
    Inventory deductStock(Long productId, int quantity);

    /** Wraps the whole transaction; database lock strategies need no wrapping. */
    default <T> T around(Long productId, Supplier<T> transactionalWork) {
        return transactionalWork.get();
    }
}
