package com.hthien.flash_sale.unit;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import com.hthien.flash_sale.concurrency.NoLockStrategy;
import com.hthien.flash_sale.concurrency.OptimisticLockStrategy;
import com.hthien.flash_sale.concurrency.RedisLockStrategy;
import com.hthien.flash_sale.exception.LockAcquisitionException;

/**
 * Unit tests for concurrency lock strategies using Mockito.
 */
@ExtendWith(MockitoExtension.class)
class LockStrategyUnitTest {

    @Mock
    private RedissonClient redissonClient;

    @Mock
    private RLock rLock;

    @Test
    @DisplayName("NoLockStrategy — executes task directly without overhead")
    void noLockStrategy_shouldExecuteTaskDirectly() throws Exception {
        NoLockStrategy strategy = new NoLockStrategy();
        AtomicInteger callCount = new AtomicInteger(0);

        String result = strategy.executeWithLock(1L, () -> {
            callCount.incrementAndGet();
            return "success";
        });

        assertThat(result).isEqualTo("success");
        assertThat(callCount.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("NoLockStrategy — propagates task exception directly")
    void noLockStrategy_shouldPropagateTaskException() {
        NoLockStrategy strategy = new NoLockStrategy();

        assertThatThrownBy(() -> strategy.executeWithLock(1L, () -> {
            throw new RuntimeException("task error");
        })).isInstanceOf(RuntimeException.class)
           .hasMessage("task error");
    }

    @Test
    @DisplayName("OptimisticLockStrategy — executes task directly")
    void optimisticLockStrategy_shouldExecuteTaskDirectly() throws Exception {
        OptimisticLockStrategy strategy = new OptimisticLockStrategy();
        AtomicInteger callCount = new AtomicInteger(0);

        String result = strategy.executeWithLock(1L, () -> {
            callCount.incrementAndGet();
            return "success";
        });

        assertThat(result).isEqualTo("success");
        assertThat(callCount.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("RedisLockStrategy — acquires lock successfully, executes task, and releases lock")
    void redisLockStrategy_whenLockAcquired_shouldExecuteAndRelease() throws Exception {
        when(redissonClient.getLock("lock:inventory:1")).thenReturn(rLock);
        when(rLock.tryLock(anyLong(), any())).thenReturn(true);
        when(rLock.isHeldByCurrentThread()).thenReturn(true);

        RedisLockStrategy strategy = new RedisLockStrategy(redissonClient);
        AtomicInteger callCount = new AtomicInteger(0);

        String result = strategy.executeWithLock(1L, () -> {
            callCount.incrementAndGet();
            return "success";
        });

        assertThat(result).isEqualTo("success");
        assertThat(callCount.get()).isEqualTo(1);
        verify(rLock).unlock();
    }

    @Test
    @DisplayName("RedisLockStrategy — fails to acquire lock and throws LockAcquisitionException")
    void redisLockStrategy_whenLockNotAcquired_shouldThrowLockAcquisitionException() throws Exception {
        when(redissonClient.getLock("lock:inventory:1")).thenReturn(rLock);
        when(rLock.tryLock(anyLong(), any())).thenReturn(false);

        RedisLockStrategy strategy = new RedisLockStrategy(redissonClient);

        assertThatThrownBy(() -> strategy.executeWithLock(1L, () -> "should not be called"))
            .isInstanceOf(LockAcquisitionException.class)
            .hasMessageContaining("Could not acquire lock");
    }

    @Test
    @DisplayName("RedisLockStrategy — always releases lock in finally block even if task throws exception")
    void redisLockStrategy_whenTaskThrows_shouldStillReleaseLock() throws Exception {
        when(redissonClient.getLock("lock:inventory:1")).thenReturn(rLock);
        when(rLock.tryLock(anyLong(), any())).thenReturn(true);
        when(rLock.isHeldByCurrentThread()).thenReturn(true);

        RedisLockStrategy strategy = new RedisLockStrategy(redissonClient);

        assertThatThrownBy(() -> strategy.executeWithLock(1L, () -> {
            throw new RuntimeException("business logic failed");
        })).isInstanceOf(RuntimeException.class);

        verify(rLock).unlock();
    }
}
