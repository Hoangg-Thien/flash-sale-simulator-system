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

    public OrderResponse createOrder(CreateOrderRequest request, String idempotencyKey) {
        long startTime = System.currentTimeMillis();

        // 1. Check idempotency
        checkIdempotency(idempotencyKey);

        // 2. Validate Product
        Product product = productRepository.findById(request.getProductId())
        .orElseThrow(() -> new ProductNotFoundException(request.getProductId()));

        // 3. Lấy Strategy theo lockMode
        LockMode lockMode = parseLockMode(request.getLockMode());
        InventoryLockStrategy strategy = getStrategy(lockMode);

        // 4. Trừ kho và tạo Order trong Transaction
        try {
            return strategy.around(request.getProductId(), () -> transactionTemplate.execute(status -> {
                // Strategy trừ kho theo cơ chế riêng của nó (NoLock, Optimistic, Pessimistic, Redis)
                strategy.deductStock(request.getProductId(), request.getQuantity());

                // Tạo và lưu đơn hàng
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
            }));

        } catch (InsufficientStockException | LockAcquisitionException | DuplicateOrderException e) {
            throw e;

        } catch (OptimisticLockException | ObjectOptimisticLockingFailureException e) {
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

    private void checkIdempotency(String idempotencyKey) {
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            orderRepository.findByIdempotencyKey(idempotencyKey).ifPresent(existingOrder -> {
            log.info("Duplicate request detected: idempotencyKey={}, orderId={}",
            idempotencyKey, existingOrder.getId());
            throw new DuplicateOrderException(existingOrder);
            });
        }
    }

    private InventoryLockStrategy getStrategy(LockMode lockMode) {
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

    private LockMode parseLockMode(String lockModeStr) {
        try {
            return LockMode.valueOf(lockModeStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                "Invalid lockMode: " + lockModeStr + ". Valid values: NONE, OPTIMISTIC, PESSIMISTIC, REDIS");
        }
    }
}
