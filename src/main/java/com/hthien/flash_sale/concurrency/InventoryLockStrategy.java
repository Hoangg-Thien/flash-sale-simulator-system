package com.hthien.flash_sale.concurrency;

import java.util.concurrent.Callable;

/**
* Strategy interface cho việc lock inventory khi mua hàng.
*
* THIẾT KẾ: mỗi strategy nhận 1 Callable (business logic trừ stock)
* và wrap nó với cơ chế lock phù hợp.
*
* Tại sao Callable thay vì Runnable?
* - Callable có thể throw Exception và return giá trị
* - Business logic (trừ stock + tạo order) có thể throw InsufficientStockException
*   → cần propagate exception ra ngoài
*/
public interface InventoryLockStrategy {

    /**
    * Thực thi business logic với cơ chế lock phù hợp.
    *
    * @param productId ID sản phẩm cần lock
    * @param task      Business logic cần được bảo vệ (trừ stock, tạo order)
    * @param <T>       Return type của task
    * @return          Kết quả của task
    * @throws Exception nếu task ném exception hoặc lock không acquire được
    */

    <T> T executeWithLock(Long productId, Callable<T> task) throws Exception;
}
