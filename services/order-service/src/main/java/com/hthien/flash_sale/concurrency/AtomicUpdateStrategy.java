package com.hthien.flash_sale.concurrency;

import java.time.Instant;

import org.springframework.stereotype.Component;

import com.hthien.flash_sale.entity.Inventory;
import com.hthien.flash_sale.exception.InsufficientStockException;
import com.hthien.flash_sale.exception.ProductNotFoundException;
import com.hthien.flash_sale.repository.InventoryRepository;

import lombok.RequiredArgsConstructor;

@Component("atomicUpdateStrategy")
@RequiredArgsConstructor
public class AtomicUpdateStrategy implements InventoryLockStrategy {

    private final InventoryRepository inventoryRepository;

    @Override
    public Inventory deductStock(Long productId, int quantity) {
        int affectedRows = inventoryRepository.deductStockAtomic(productId, quantity, Instant.now());
        // The bulk update clears the persistence context before this read.
        Inventory inventory = inventoryRepository.findByProductId(productId)
            .orElseThrow(() -> new ProductNotFoundException(productId));
        if (affectedRows == 0) {
            throw new InsufficientStockException(productId, quantity, inventory.getStock());
        }
        return inventory;
    }
}
