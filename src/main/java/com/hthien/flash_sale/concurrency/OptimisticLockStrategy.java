package com.hthien.flash_sale.concurrency;

import java.util.concurrent.Callable;

import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * OptimisticLockStrategy — dựa vào @Version trong Inventory entity.
 *
 * CÁCH HOẠT ĐỘNG:
 * - Inventory.version tăng sau mỗi UPDATE
 * - Hibernate tự thêm: UPDATE inventory SET stock=?, version=? WHERE id=? AND version=?
 * - Nếu 2 thread cùng đọc version=5:
 *     Thread A: UPDATE ... WHERE version=5 → success, version trở thành 6
 *     Thread B: UPDATE ... WHERE version=5 → 0 rows affected → OptimisticLockException
 *
 * Strategy này KHÔNG cần làm gì ngoài gọi task — Hibernate tự handle version check.
 * Exception được bắt ở tầng OrderService, map sang HTTP 409.
 *
 * TRADE-OFF khi tải cao:
 * - 20 thread tranh 1 sản phẩm → 19 thread bị retry storm
 * - Phù hợp khi tỷ lệ conflict THẤP (nhiều sản phẩm, ít người mua cùng lúc)
 * - KHÔNG phù hợp với kịch bản flash-sale (conflict rate ~95%)
*/

@Component("optimisticLockStrategy")
@Slf4j
public class OptimisticLockStrategy implements InventoryLockStrategy{
 @Override
    public <T> T executeWithLock(Long productId, Callable<T> task) throws Exception {
        log.debug("OptimisticLockStrategy: executing with @Version check for productId={}", productId);
        // Không cần làm gì thêm — @Version trong Inventory entity tự handle
        // Nếu conflict xảy ra: Hibernate throw OptimisticLockException
        // → bắt ở OrderService → throw InsufficientStockException (409)
        return task.call();
    }
}
