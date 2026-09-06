package com.hthien.flash_sale.concurrency;

import com.hthien.flash_sale.entity.Inventory;
public interface InventoryLockStrategy {

    /**
    * Thực hiện load kho, kiểm tra và trừ tồn kho theo chiến lược concurrency tương ứng.
    *
    * @param productId ID sản phẩm
    * @param quantity  Số lượng muốn mua
    * @return Inventory sau khi đã trừ kho thành công
    */
    Inventory deductStock(Long productId, int quantity);
}
