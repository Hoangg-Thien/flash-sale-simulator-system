package com.hthien.flash_sale;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.*;
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
 * Unit test cho các lock strategy.
 * Dùng Mockito — không cần Testcontainers vì:
 * - Test strategy logic (acquire/release), không test business logic (trừ stock)
 * - Testcontainers sẽ được dùng trong Phase 5 (integration test với concurrent load)
 */
@ExtendWith(MockitoExtension.class)
class LockStrategyTest {

    @Mock
    private RedissonClient redissonClient;

    @Mock
    private RLock rLock;

    // TEST 1: NoLockStrategy — task được gọi trực tiếp
    @Test
    @DisplayName("NoLockStrategy — task được gọi trực tiếp, không có overhead")
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

    // TEST 2: NoLockStrategy — exception propagate đúng
    @Test
    @DisplayName("NoLockStrategy — exception từ task được propagate ra ngoài")
    void noLockStrategy_shouldPropagateTaskException() {
        NoLockStrategy strategy = new NoLockStrategy();

        assertThatThrownBy(() -> strategy.executeWithLock(1L, () -> {
            throw new RuntimeException("task error");
        })).isInstanceOf(RuntimeException.class)
           .hasMessage("task error");
    }

    // TEST 3: OptimisticLockStrategy — task được gọi, không có thêm logic
    @Test
    @DisplayName("OptimisticLockStrategy — task được gọi, không thêm overhead")
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

    // TEST 4: RedisLockStrategy — acquire thành công → task được gọi → lock released
    @Test
    @DisplayName("RedisLockStrategy — acquire thành công, task chạy, lock released")
    void redisLockStrategy_whenLockAcquired_shouldExecuteAndRelease() throws Exception {
        // Arrange
        when(redissonClient.getLock("lock:inventory:1")).thenReturn(rLock);
        when(rLock.tryLock(anyLong(), anyLong(), any())).thenReturn(true);
        when(rLock.isHeldByCurrentThread()).thenReturn(true);

        RedisLockStrategy strategy = new RedisLockStrategy(redissonClient);
        AtomicInteger callCount = new AtomicInteger(0);

        // Act
        String result = strategy.executeWithLock(1L, () -> {
            callCount.incrementAndGet();
            return "success";
        });

        // Assert
        assertThat(result).isEqualTo("success");
        assertThat(callCount.get()).isEqualTo(1);
        verify(rLock).unlock(); // lock phải được release
    }

    // TEST 5: RedisLockStrategy — acquire thất bại → LockAcquisitionException
    @Test
    @DisplayName("RedisLockStrategy — acquire thất bại → LockAcquisitionException (không treo thread)")
    void redisLockStrategy_whenLockNotAcquired_shouldThrowLockAcquisitionException() throws Exception {
        // Arrange
        when(redissonClient.getLock("lock:inventory:1")).thenReturn(rLock);
        when(rLock.tryLock(anyLong(), anyLong(), any())).thenReturn(false); // lock không available

        RedisLockStrategy strategy = new RedisLockStrategy(redissonClient);

        // Act & Assert
        assertThatThrownBy(() -> strategy.executeWithLock(1L, () -> "should not be called"))
            .isInstanceOf(LockAcquisitionException.class)
            .hasMessageContaining("Could not acquire lock");
    }

    // TEST 6: RedisLockStrategy — task throw exception → lock vẫn được release
    @Test
    @DisplayName("RedisLockStrategy — task throw exception → lock vẫn được release trong finally")
    void redisLockStrategy_whenTaskThrows_shouldStillReleaseLock() throws Exception {
        // Arrange
        when(redissonClient.getLock("lock:inventory:1")).thenReturn(rLock);
        when(rLock.tryLock(anyLong(), anyLong(), any())).thenReturn(true);
        when(rLock.isHeldByCurrentThread()).thenReturn(true);

        RedisLockStrategy strategy = new RedisLockStrategy(redissonClient);

        // Act & Assert
        assertThatThrownBy(() -> strategy.executeWithLock(1L, () -> {
            throw new RuntimeException("business logic failed");
        })).isInstanceOf(RuntimeException.class);

        // Lock vẫn được release dù task fail
        verify(rLock).unlock();
    }
}
