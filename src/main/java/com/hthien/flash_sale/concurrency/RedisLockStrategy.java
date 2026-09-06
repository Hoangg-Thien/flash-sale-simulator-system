package com.hthien.flash_sale.concurrency;

import java.util.concurrent.TimeUnit;

import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.hthien.flash_sale.entity.Inventory;
import com.hthien.flash_sale.exception.InsufficientStockException;
import com.hthien.flash_sale.exception.LockAcquisitionException;
import com.hthien.flash_sale.exception.ProductNotFoundException;
import com.hthien.flash_sale.repository.InventoryRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Component("redisLockStrategy")
@Slf4j
@RequiredArgsConstructor
public class RedisLockStrategy implements InventoryLockStrategy{
    
    private final RedissonClient redissonClient;
    private final InventoryRepository inventoryRepository;

    @Value("${app.lock.wait-time-seconds:2}")
    private long waitTimeSeconds;

    private static final String LOCK_KEY_PREFIX = "lock:inventory:";

    @Override
    public Inventory deductStock(Long productId, int quantity){

        String lockKey = LOCK_KEY_PREFIX + productId;
        RLock lock = redissonClient.getLock(lockKey);

        boolean acquired;
        try {
            acquired = lock.tryLock(waitTimeSeconds, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.error("Redis unreachable: {}", e.getMessage());
            throw new LockAcquisitionException(productId);
        }

        if(!acquired){
            throw new LockAcquisitionException(productId);
        }
        try {
            Inventory inventory = inventoryRepository.findByProductId(productId)
            .orElseThrow(() -> new ProductNotFoundException(productId));

            if(inventory.getStock() < quantity){
                throw new InsufficientStockException(productId, quantity, inventory.getStock());
            }
            inventory.setStock(inventory.getStock() - quantity);
            return inventoryRepository.save(inventory);
        } finally {
            if(lock.isHeldByCurrentThread()){
                lock.unlock();
            }
        }
    }
}
