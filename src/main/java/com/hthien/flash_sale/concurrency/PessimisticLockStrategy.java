package com.hthien.flash_sale.concurrency;

import org.springframework.stereotype.Component;

import com.hthien.flash_sale.entity.Inventory;
import com.hthien.flash_sale.exception.InsufficientStockException;
import com.hthien.flash_sale.exception.ProductNotFoundException;
import com.hthien.flash_sale.repository.InventoryRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Component("pessimisticLockStrategy")
@RequiredArgsConstructor
@Slf4j
public class PessimisticLockStrategy implements InventoryLockStrategy{
     
    private final InventoryRepository inventoryRepository;

    @Override
    public Inventory deductStock(Long productId, int quantity) {
        // Khóa trực tiếp hàng dữ liệu trong Postgres: SELECT FOR UPDATE

        Inventory inventory = inventoryRepository.findByProductIdForUpdate(productId)
        .orElseThrow(() -> new ProductNotFoundException(productId));
        
        if (inventory.getStock() < quantity) {
            throw new InsufficientStockException(productId, quantity, inventory.getStock());
        }

        inventory.setStock(inventory.getStock() - quantity);
        return inventoryRepository.save(inventory);
    }
}
