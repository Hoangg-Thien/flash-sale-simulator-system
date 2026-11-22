package com.hthien.inventory.concurrency;

import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import com.hthien.inventory.repository.InventoryRepository;
import org.redisson.api.RedissonClient;
import org.redisson.client.RedisException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class InventoryLockStrategy {
    private static final Logger log = LoggerFactory.getLogger(InventoryLockStrategy.class);

    enum Mode {
        ATOMIC, PESSIMISTIC, OPTIMISTIC, REDIS, NONE
    }

    private final InventoryRepository inventory;
    private final Mode mode;
    private final ObjectProvider<RedissonClient> redis;
    private final long waitTimeSeconds;

    InventoryLockStrategy(InventoryRepository inventory, @Value("${inventory.lock-mode}") Mode mode,
                          @Value("${inventory.unsafe-modes-enabled}") boolean unsafeModesEnabled,
                          @Value("${inventory.lock-wait-seconds:2}") long waitTimeSeconds,
                          ObjectProvider<RedissonClient> redis) {
        if (mode == Mode.NONE && !unsafeModesEnabled) {
            throw new IllegalArgumentException("Inventory lock mode NONE requires inventory.unsafe-modes-enabled=true");
        }
        this.inventory = inventory;
        this.mode = mode;
        this.redis = redis;
        this.waitTimeSeconds = waitTimeSeconds;
    }

    public boolean deduct(long productId, int quantity) {
        if (mode == Mode.ATOMIC) {
            return inventory.deductAtomically(productId, quantity);
        }

        var current = (mode == Mode.PESSIMISTIC ? inventory.lock(productId) : inventory.find(productId)).orElse(null);
        if (current == null || current.stock() < quantity) {
            return false;
        }

        int remaining = current.stock() - quantity;
        return switch (mode) {
            case NONE -> {
                // Deliberately widen the stale-read window for the lab lost-update demo.
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Stock deduction interrupted", e);
                }
                inventory.updateStock(productId, remaining);
                yield true;
            }
            default -> inventory.updateIfVersionMatches(current, remaining);
        };
    }

    public <T> T around(long productId, Supplier<T> transaction) {
        if (mode != Mode.REDIS) {
            return transaction.get();
        }

        var lock = redis.getObject().getLock("lock:inventory-service:" + productId);
        try {
            if (!lock.tryLock(waitTimeSeconds, TimeUnit.SECONDS)) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Inventory lock unavailable");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Inventory lock interrupted", e);
        } catch (RedisException e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Inventory lock unavailable", e);
        }

        try {
            return transaction.get();
        } finally {
            try {
                lock.unlock();
            } catch (RedisException | IllegalMonitorStateException e) {
                log.warn("Could not unlock inventory product {}", productId, e);
            }
        }
    }
}
