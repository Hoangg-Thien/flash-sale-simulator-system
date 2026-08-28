package com.hthien.flash_sale.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.hthien.flash_sale.entity.Order;


@Repository
public interface OrderRepository extends JpaRepository<Order, Long>{

    /*
    Tìm Order theo idempotency key — dùng để check duplicate
    Optional.empty() → chưa có → tạo mới
    Optional.of(order) → đã có → trả lại kết quả cũ
    */
    Optional<Order> findByIdempotencyKey(String idempotencyKey);
}
