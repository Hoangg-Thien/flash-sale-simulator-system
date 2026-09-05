package com.hthien.flash_sale.concurrency;

import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.redisson.client.RedisException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.hthien.flash_sale.exception.LockAcquisitionException;

import io.netty.handler.timeout.TimeoutException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * RedisLockStrategy — Distributed Lock qua Redis/Redisson.
 *
 * LOCK KEY: "lock:inventory:{productId}"
 * - Phạm vi khóa đúng = 1 sản phẩm, không lock rộng hơn cần thiết
 * - "lock:inventory:" prefix để tránh conflict với các key Redis khác
 *
 * LUỒNG:
 * 1. tryLock(waitTime=2s, leaseTime=10s):
 *    - Nếu lock free: acquire ngay
 *    - Nếu lock đang giữ: chờ tối đa 2s
 *    - Sau 2s chưa lấy được: return false → throw LockAcquisitionException
 * 2. Redisson WATCHDOG tự động gia hạn leaseTime nếu task vẫn chạy
 *    → Giải quyết vấn đề "TTL hết trước khi xử lý xong" của tự viết SETNX
 * 3. finally: release lock (chỉ owner mới release được — Redisson tự check)
 *    → Thread A không thể release lock của Thread B
*/
@Component("redisLockStrategy")
@Slf4j
@RequiredArgsConstructor
public class RedisLockStrategy implements InventoryLockStrategy{
    
    private final RedissonClient redissonClient;

    @Value("${app.lock.wait-time-seconds:2}")
    private long waitTimeSeconds;


    private static final String LOCK_KEY_PREFIX = "lock:inventory:";

    @Override
    public <T> T executeWithLock(Long productId, Callable<T> task) throws Exception {
        String lockKey = LOCK_KEY_PREFIX + productId;
        RLock lock = redissonClient.getLock(lockKey);

        log.debug("RedisLockStrategy: trying to acquire lock: key={}, waitTime={}s (Watchdog enabled)",
        lockKey, waitTimeSeconds);

        long lockStart = System.currentTimeMillis();

        
        // tryLock: non-blocking acquire với timeout
        // Khác lock(): lock() block vô hạn → nguy hiểm nếu task treo
        boolean acquired;
        try {
            acquired = lock.tryLock(waitTimeSeconds, TimeUnit.SECONDS);
        } catch (RedisException | TimeoutException e) {
            log.error("Redis is down or unreachable when acquiring lock for productId={}: {}", productId, e.getMessage());
            throw new LockAcquisitionException(productId);
        }

        if(!acquired){
            log.warn("RedisLockStrategy: failed to acquire lock: key={} after {}s",
            lockKey, waitTimeSeconds);
            throw new LockAcquisitionException(productId);
        }

        long lockAcquiredTime = System.currentTimeMillis() - lockStart;
        log.debug("RedisLockStrategy: lock acquired: key={} in {}ms", lockKey, lockAcquiredTime);

        try {
           return task.call(); 
        } finally {
            // Release LUÔN trong finally — dù task thành công hay throw exception
            // isHeldByCurrentThread(): chỉ release nếu thread hiện tại đang giữ lock
            // → tránh trường hợp TTL hết, lock đã bị release tự động → không release 2 lần
            try {
                if(lock.isHeldByCurrentThread()){
                    lock.unlock();
                    log.debug("RedisLockStrategy: lock released: key={}", lockKey);
                }
            } catch (Exception e) {
                log.warn("Failed to release Redis lock: {}", e.getMessage());
            }
        }
    }
}
