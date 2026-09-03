package com.hthien.flash_sale.service;

import java.time.Instant;
import java.util.Map;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.hthien.flash_sale.concurrency.InventoryLockStrategy;
import com.hthien.flash_sale.dto.request.CreateOrderRequest;
import com.hthien.flash_sale.dto.response.OrderResponse;
import com.hthien.flash_sale.entity.Inventory;
import com.hthien.flash_sale.entity.Order;
import com.hthien.flash_sale.entity.OrderItem;
import com.hthien.flash_sale.entity.Product;
import com.hthien.flash_sale.enums.LockMode;
import com.hthien.flash_sale.enums.OrderStatus;
import com.hthien.flash_sale.exception.DuplicateOrderException;
import com.hthien.flash_sale.exception.InsufficientStockException;
import com.hthien.flash_sale.exception.LockAcquisitionException;
import com.hthien.flash_sale.exception.ProductNotFoundException;
import com.hthien.flash_sale.repository.InventoryRepository;
import com.hthien.flash_sale.repository.OrderRepository;
import com.hthien.flash_sale.repository.ProductRepository;

import jakarta.persistence.OptimisticLockException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
@RequiredArgsConstructor
public class OrderService {
    
    private final ProductRepository productRepository;
    private final OrderRepository orderRepository;
    private final InventoryRepository inventoryRepository;
    private final TransactionTemplate transactionTemplate;
    private final Map<String, InventoryLockStrategy> lockStrategies; 

    public OrderResponse createOrder(CreateOrderRequest request, String idempotencyKey){
        long startTime = System.currentTimeMillis();

        /*
        Idempotency check
        Nếu client gửi kèm Idempotency-Key, check xem key này đã xử lý chưa
        */
       if(idempotencyKey != null && !idempotencyKey.isBlank()){
        orderRepository.findByIdempotencyKey(idempotencyKey).ifPresent(existingOrder -> {
            log.info("Duplicate request detected: idempotencyKey={}, orderId={}",
            idempotencyKey, existingOrder.getId());
            throw new DuplicateOrderException(existingOrder);
        });
       }

       // Validate Product
       Product product = productRepository.findById(request.getProductId())
       .orElseThrow(() -> new ProductNotFoundException(request.getProductId()));

       // Parse và validate lockMode
       LockMode lockMode = parseLockMode(request.getLockMode());

       // Lấy đúng strategy theo lockMode
       InventoryLockStrategy strategy = getStrategy(lockMode);

        try {
            return strategy.executeWithLock(request.getProductId(),
                () -> transactionTemplate.execute(status ->
                executePurchase(product, request, lockMode, idempotencyKey, startTime)
                )
            );
        } catch (InsufficientStockException | LockAcquisitionException | DuplicateOrderException e) {
            throw e; // re-throw business exceptions không bọc
        } catch (OptimisticLockException | ObjectOptimisticLockingFailureException e) {
            // Bắt cả JPA OptimisticLockException và Spring wrapper
            log.warn("Optimistic lock conflict: productId={}", request.getProductId());
            throw new InsufficientStockException(request.getProductId(), request.getQuantity(), 0);
        } catch (Exception e) {
            if (e.getCause() instanceof InsufficientStockException ise) {
                throw ise;
            }
            if (e.getCause() instanceof LockAcquisitionException lae) {
                throw lae;
            }
            if (e.getCause() instanceof DuplicateOrderException doe) {
                throw doe;
            }
            if (e.getCause() instanceof OptimisticLockException
                || e.getCause() instanceof ObjectOptimisticLockingFailureException) {
                log.warn("Optimistic lock conflict (wrapped): productId={}", request.getProductId());
                throw new InsufficientStockException(request.getProductId(), request.getQuantity(), 0);
            }
            throw new RuntimeException("Unexpected error during purchase", e);
        }
    }
    
    /**
     * Business logic mua hàng — được wrap bởi strategy.
     * Method này được gọi TRONG executeWithLock() của strategy qua TransactionTemplate.
     */
    private OrderResponse executePurchase(Product product, CreateOrderRequest request, LockMode lockMode, String idempotencyKey, long startTime){

        Inventory inventory;
        // load inventory
        if(lockMode == LockMode.PESSIMISTIC){
            inventory = inventoryRepository.findByProductIdForUpdate(request.getProductId())
            .orElseThrow(() -> new ProductNotFoundException(request.getProductId()));
        } else {
            inventory = inventoryRepository.findByProductId(request.getProductId())
            .orElseThrow(() -> new ProductNotFoundException(request.getProductId()));
        }

        // check stock
        if(inventory.getStock() < request.getQuantity()){
            log.warn("Insufficient stock: productId={}, requested={}, available={}",
            request.getProductId(), request.getQuantity(), inventory.getStock());
            throw new InsufficientStockException(
            request.getProductId(), request.getQuantity(), inventory.getStock());
        }

        // Giả lập race window chỉ khi mode = NONE (để Phase 3 test tái hiện bug)
        if (lockMode == LockMode.NONE) {
            try { Thread.sleep(10); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        // trừ stock
        if (lockMode == LockMode.NONE) {
            inventoryRepository.updateStockUnsafe(request.getProductId(), inventory.getStock() - request.getQuantity());
        } else {
            inventory.setStock(inventory.getStock() - request.getQuantity());
            inventoryRepository.save(inventory);
        }

        // Tạo Order + OrderItem
        Order order = Order.builder()
        .product(product)
        .orderStatus(OrderStatus.SUCCESS)
        .idempotencyKey(idempotencyKey)
        .lockMode(lockMode)
        .requestedAt(Instant.ofEpochMilli(startTime))
        .build();

        OrderItem item = OrderItem.builder()
        .order(order)
        .product(product)
        .quantity(request.getQuantity())
        .unitPrice(product.getPrice())
        .build();

        order.getItems().add(item);

        try {
            order = orderRepository.save(order);
            long endTime = System.currentTimeMillis();
            order.setLatencyMs(endTime - startTime);
            order.setCompletedAt(Instant.ofEpochMilli(endTime));
            order = orderRepository.save(order);
            log.info("Order created: id={}, productId={}, lockMode={}",
                order.getId(), product.getId(), lockMode);
        } catch (DataIntegrityViolationException e) {
            log.warn("DataIntegrityViolation on idempotency key={}", idempotencyKey);
            Order existingOrder = orderRepository.findByIdempotencyKey(idempotencyKey)
                .orElseThrow(() -> e);
            throw new DuplicateOrderException(existingOrder);
        }
        return OrderResponse.from(order);
    }

    private InventoryLockStrategy getStrategy(LockMode lockMode){
        String beanName = switch (lockMode) {
            case NONE -> "noLockStrategy";
            case OPTIMISTIC -> "optimisticLockStrategy";
            case PESSIMISTIC -> "pessimisticLockStrategy";
            case REDIS -> "redisLockStrategy";
        };
        InventoryLockStrategy strategy = lockStrategies.get(beanName);
        if (strategy == null) {
            throw new IllegalStateException("No strategy found for lockMode: " + lockMode);
        }
        return strategy;
    }

    private LockMode parseLockMode(String lockModeStr){
        try {
            return LockMode.valueOf(lockModeStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
            "Invalid lockMode: " + lockModeStr +". Valid values: NONE, OPTIMISTIC, PESSIMISTIC, REDIS");
        }
    }
}
