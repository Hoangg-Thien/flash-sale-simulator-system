package com.hthien.flash_sale.concurrency;

import java.util.concurrent.Callable;

import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * PessimisticLockStrategy — dùng SELECT ... FOR UPDATE (DB row lock).
 *
 * CÁCH HOẠT ĐỘNG:
 * - Khi gọi inventoryRepo.findByProductIdForUpdate(productId), Postgres thực thi:
 *     SELECT * FROM inventory WHERE product_id = ? FOR UPDATE
 * - Row lock được acquire → các transaction khác cố SELECT FOR UPDATE cùng row sẽ BLOCK
 * - Lock tự release khi transaction commit/rollback (không cần code release thủ công)
 *
 * Đặc điểm:
 * - Đúng dù nhiều Spring Boot instance (lock ở tầng Postgres, không phải JVM)
 * - Không cần retry logic (request tự block chờ lock)
 * - Performance khi tải cao: tốt (thread tự xếp hàng ở DB)
 *
 * Strategy này KHÔNG cần làm gì thêm — InventoryRepository.findByProductIdForUpdate()
 * đã có @Lock(PESSIMISTIC_WRITE). OrderService cần GỌI ĐÚNG method này khi mode=PESSIMISTIC.
*/
@Component("pessimisticLockStrategy")
@Slf4j
public class PessimisticLockStrategy implements InventoryLockStrategy{
    
    @Override
    public <T> T executeWithLock(Long productId, Callable<T> task) throws Exception {
        log.debug("PessimisticLockStrategy: DB row lock acquired for productId={}", productId);
        // Lock thực sự nằm trong findByProductIdForUpdate() được gọi TRONG task
        // (task là lambda trong OrderService, sẽ gọi đúng method dựa vào lockMode)
        return task.call();
    }
}
