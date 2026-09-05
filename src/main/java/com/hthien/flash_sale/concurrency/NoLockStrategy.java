package com.hthien.flash_sale.concurrency;

import java.util.concurrent.Callable;

import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
* NoLockStrategy — cố ý KHÔNG lock, để tái hiện race condition.
*
* Đây là implementation dùng cho mode NONE:
* - Chỉ gọi task() trực tiếp, không có bất kỳ cơ chế đồng bộ nào
* - Dùng làm baseline để so sánh: "không có lock → oversell"
*/

@Component("noLockStrategy")
@Slf4j
public class NoLockStrategy implements InventoryLockStrategy {

    @Override
    public <T> T executeWithLock(Long productId, Callable<T> task) throws Exception{
        log.debug("NoLockStrategy: executing without lock for productId={}", productId);

        return task.call();
    }
}
