package com.hthien.flash_sale.unit;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import com.hthien.flash_sale.concurrency.NoLockStrategy;
import com.hthien.flash_sale.concurrency.OptimisticLockStrategy;
import com.hthien.flash_sale.concurrency.PessimisticLockStrategy;
import com.hthien.flash_sale.concurrency.RedisLockStrategy;
import com.hthien.flash_sale.entity.Inventory;
import com.hthien.flash_sale.exception.InsufficientStockException;
import com.hthien.flash_sale.exception.LockAcquisitionException;
import com.hthien.flash_sale.exception.ProductNotFoundException;
import com.hthien.flash_sale.repository.InventoryRepository;

@ExtendWith(MockitoExtension.class)
class LockStrategyUnitTest {

    @Mock
    private InventoryRepository inventoryRepository;

    @Mock
    private RedissonClient redissonClient;

    @Mock
    private RLock rLock;

    private Inventory inventory;

    @BeforeEach
    void setUp() {
        inventory = Inventory.builder()
            .id(1L)
            .stock(10)
            .build();
    }

    @Test
    @DisplayName("NoLockStrategy — deducts stock and calls updateStockUnsafe")
    void noLockStrategy_shouldDeductStockUnsafe() {
        when(inventoryRepository.findByProductId(1L)).thenReturn(Optional.of(inventory));

        NoLockStrategy strategy = new NoLockStrategy(inventoryRepository);
        Inventory result = strategy.deductStock(1L, 2);

        assertThat(result).isNotNull();
        verify(inventoryRepository).updateStockUnsafe(1L, 8);
    }

    @Test
    @DisplayName("NoLockStrategy — throws InsufficientStockException when stock < quantity")
    void noLockStrategy_whenInsufficientStock_shouldThrow() {
        when(inventoryRepository.findByProductId(1L)).thenReturn(Optional.of(inventory));

        NoLockStrategy strategy = new NoLockStrategy(inventoryRepository);
        assertThatThrownBy(() -> strategy.deductStock(1L, 20))
            .isInstanceOf(InsufficientStockException.class);
    }

    @Test
    @DisplayName("OptimisticLockStrategy — deducts stock and saves entity")
    void optimisticLockStrategy_shouldDeductStockAndSave() {
        when(inventoryRepository.findByProductId(1L)).thenReturn(Optional.of(inventory));
        when(inventoryRepository.save(any(Inventory.class))).thenAnswer(invocation -> invocation.getArgument(0));

        OptimisticLockStrategy strategy = new OptimisticLockStrategy(inventoryRepository);
        Inventory result = strategy.deductStock(1L, 3);

        assertThat(result.getStock()).isEqualTo(7);
        verify(inventoryRepository).save(inventory);
    }

    @Test
    @DisplayName("PessimisticLockStrategy — uses findByProductIdForUpdate and saves entity")
    void pessimisticLockStrategy_shouldUseRowLockAndSave() {
        when(inventoryRepository.findByProductIdForUpdate(1L)).thenReturn(Optional.of(inventory));
        when(inventoryRepository.save(any(Inventory.class))).thenAnswer(invocation -> invocation.getArgument(0));

        PessimisticLockStrategy strategy = new PessimisticLockStrategy(inventoryRepository);
        Inventory result = strategy.deductStock(1L, 4);

        assertThat(result.getStock()).isEqualTo(6);
        verify(inventoryRepository).findByProductIdForUpdate(1L);
        verify(inventoryRepository).save(inventory);
    }

    @Test
    @DisplayName("RedisLockStrategy — acquires distributed lock, deducts stock and releases lock")
    void redisLockStrategy_whenLockAcquired_shouldDeductAndRelease() throws Exception {
        when(redissonClient.getLock("lock:inventory:1")).thenReturn(rLock);
        when(rLock.tryLock(anyLong(), any())).thenReturn(true);
        when(rLock.isHeldByCurrentThread()).thenReturn(true);
        when(inventoryRepository.findByProductId(1L)).thenReturn(Optional.of(inventory));
        when(inventoryRepository.save(any(Inventory.class))).thenAnswer(invocation -> invocation.getArgument(0));

        RedisLockStrategy strategy = new RedisLockStrategy(redissonClient, inventoryRepository);
        Inventory result = strategy.around(1L, () -> strategy.deductStock(1L, 1));

        assertThat(result.getStock()).isEqualTo(9);
        verify(rLock).unlock();
    }

    @Test
    @DisplayName("RedisLockStrategy — throws LockAcquisitionException when lock cannot be acquired")
    void redisLockStrategy_whenLockNotAcquired_shouldThrow() throws Exception {
        when(redissonClient.getLock("lock:inventory:1")).thenReturn(rLock);
        when(rLock.tryLock(anyLong(), any())).thenReturn(false);

        RedisLockStrategy strategy = new RedisLockStrategy(redissonClient, inventoryRepository);

        assertThatThrownBy(() -> strategy.around(1L, () -> strategy.deductStock(1L, 1)))
            .isInstanceOf(LockAcquisitionException.class);

        verify(inventoryRepository, never()).findByProductId(any());
    }

    @Test
    @DisplayName("RedisLockStrategy — releases lock in finally block even when exception occurs during deduct")
    void redisLockStrategy_whenExceptionDuringDeduct_shouldStillReleaseLock() throws Exception {
        when(redissonClient.getLock("lock:inventory:1")).thenReturn(rLock);
        when(rLock.tryLock(anyLong(), any())).thenReturn(true);
        when(rLock.isHeldByCurrentThread()).thenReturn(true);
        when(inventoryRepository.findByProductId(1L)).thenReturn(Optional.empty());

        RedisLockStrategy strategy = new RedisLockStrategy(redissonClient, inventoryRepository);

        assertThatThrownBy(() -> strategy.around(1L, () -> strategy.deductStock(1L, 1)))
            .isInstanceOf(ProductNotFoundException.class);

        verify(rLock).unlock();
    }
}
