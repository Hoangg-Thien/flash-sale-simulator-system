package com.hthien.flash_sale.service;

import java.time.Instant;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
import com.hthien.flash_sale.exception.ProductNotFoundException;
import com.hthien.flash_sale.repository.InventoryRepository;
import com.hthien.flash_sale.repository.OrderRepository;
import com.hthien.flash_sale.repository.ProductRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
@RequiredArgsConstructor
public class OrderService {
    
    private final ProductRepository productRepository;
    private final OrderRepository orderRepository;
    private final InventoryRepository inventoryRepository;

    @Transactional
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

       // Đọc Inventory
       Inventory inventory = inventoryRepository.findByProductId(request.getProductId())
       .orElseThrow(() -> new ProductNotFoundException(request.getProductId()));

       // Kiểm tra Stock
       if(inventory.getStock() < request.getQuantity()){

        log.warn("Insufficient stock: productId={}, requested={}, available={}",
        request.getProductId(), request.getQuantity(), inventory.getStock());

        throw new InsufficientStockException(
        request.getProductId(), request.getQuantity(), inventory.getStock());
       }

       // Giả lập processing / race window giữa Read và Write khi KHÔNG dùng lock
       if (lockMode == LockMode.NONE) {
           try {
               Thread.sleep(10); // 10ms giả lập xử lý business/payment
           } catch (InterruptedException e) {
               Thread.currentThread().interrupt();
           }
       }

       // Trừ Stock
       inventory.setStock(inventory.getStock() - request.getQuantity());
       inventoryRepository.save(inventory);

       // Tạo Order
       Order order = Order.builder()
        .product(product)
        .orderStatus(OrderStatus.SUCCESS)
        .idempotencyKey(idempotencyKey)
        .lockMode(lockMode)
        .requestedAt(Instant.ofEpochMilli(startTime))
        .build();

        // Tạo OrderItem
        OrderItem item = OrderItem.builder()
        .order(order)
        .product(product)
        .quantity(request.getQuantity())
        .unitPrice(product.getPrice()) 
        .build();

        order.getItems().add(item);

        /* 
        Save Order
        Nếu 2 request cùng idempotency key race qua bước 1 (cả 2 thấy "chưa có"),
        thì 1 trong 2 sẽ bị UNIQUE constraint violation ở đây → bắt và xử lý
        */
       try {
        order = orderRepository.save(order);
        
        long endTime = System.currentTimeMillis();
        order.setLatencyMs(endTime - startTime);
        order.setCompletedAt(Instant.ofEpochMilli(endTime));
        order = orderRepository.save(order);

        log.info("Order created: id={}, productId={}, lockMode={}",
        order.getId(), product.getId(), lockMode);
       } catch (DataIntegrityViolationException e) {
        log.warn("DataIntegrityViolation on idempotency key={}, fetching existing order",idempotencyKey);
        Order existingOrder = orderRepository.findByIdempotencyKey(idempotencyKey)
        .orElseThrow(() -> e);
        throw new DuplicateOrderException(existingOrder);
       }
       return OrderResponse.from(order);
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
