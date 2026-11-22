package com.hthien.flash_sale.concurrency;

import org.springframework.stereotype.Component;

import com.hthien.flash_sale.entity.Inventory;
import com.hthien.flash_sale.exception.InsufficientStockException;
import com.hthien.flash_sale.exception.ProductNotFoundException;
import com.hthien.flash_sale.repository.InventoryRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Component("noLockStrategy")
@RequiredArgsConstructor 
@Slf4j
public class NoLockStrategy implements InventoryLockStrategy {

    private final InventoryRepository inventoryRepository;

    @Override
    public Inventory deductStock(Long productId, int quantity){
        Inventory inventory = inventoryRepository.findByProductId(productId)
        .orElseThrow(() -> new ProductNotFoundException(productId));

        if(inventory.getStock() < quantity){
            throw new InsufficientStockException(productId, quantity, inventory.getStock());
        }

        // Tạo race window cố ý để chứng minh bug oversell khi không có lock
        try {
            Thread.sleep(10);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Cố ý ghi đè không qua atomic / version để gây mất dữ liệu (Lost Update)
        inventoryRepository.updateStockUnsafe(productId, inventory.getStock() - quantity);
        return inventory;
    }
}
