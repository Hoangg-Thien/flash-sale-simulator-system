package com.hthien.flash_sale.service;

import java.util.List;

import com.hthien.flash_sale.client.ReservationClient;
import com.hthien.flash_sale.client.ReservationClient.Command;
import com.hthien.flash_sale.client.ReservationClient.State;
import com.hthien.flash_sale.dto.request.CreateOrderRequest;
import com.hthien.flash_sale.dto.response.OrderResponse;
import com.hthien.flash_sale.enums.OrderStatus;
import com.hthien.flash_sale.exception.ProductNotFoundException;
import com.hthien.flash_sale.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class OrderService {
    public record Purchase(OrderResponse order, boolean created) {}
    private record Claim(long id, boolean created) {}
    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final OrderRepository orders;
    private final ReservationClient inventory;
    @Value("${orders.reconcile-age-ms:5000}") private long reconcileAge;
    @Value("${orders.cancel-age-ms:60000}") private long cancelAge;
    @Value("${inventory.lock-mode:ATOMIC}") private String mode;

    public OrderResponse createOrder(CreateOrderRequest request, String key) {
        return purchase(request, key).order();
    }

    public Purchase purchase(CreateOrderRequest request, String key) {
        if (key == null || key.isBlank() || key.length() > 64)
            throw new IllegalArgumentException("Idempotency-Key must contain 1 to 64 characters");
        Claim claim = tx.execute(status -> claim(request, key));
        if (claim.created()) {
            var command = new Command(claim.id(), request.getProductId(), request.getQuantity());
            finish(claim.id(), inventory.reserve(command), false);
        }
        return new Purchase(get(claim.id()), claim.created());
    }

    private Claim claim(CreateOrderRequest request, String key) {
        var existing = orders.findByIdempotencyKey(key);
        if (existing.isPresent()) return replay(request, existing.get());
        if (request.getLockMode() != null && !mode.equalsIgnoreCase(request.getLockMode()))
            throw new IllegalArgumentException("Inventory lock mode is configured per deployment");
        var ids = jdbc.sql("""
            INSERT INTO orders(product_id,order_status,idempotency_key,lock_mode,requested_at,next_attempt_at)
            SELECT id,'PENDING',?,?,now(),now() + ? * interval '1 millisecond'
            FROM products WHERE id=? AND pending_initial_stock IS NULL
            ON CONFLICT (idempotency_key) WHERE idempotency_key IS NOT NULL DO NOTHING RETURNING id
            """).params(key, mode, reconcileAge, request.getProductId()).query(Long.class).list();
        if (ids.isEmpty()) {
            var winner = orders.findByIdempotencyKey(key);
            if (winner.isPresent()) return replay(request, winner.get());
            if (jdbc.sql("SELECT id FROM products WHERE id=?").param(request.getProductId()).query(Long.class).optional().isEmpty())
                throw new ProductNotFoundException(request.getProductId());
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Stock initialization pending");
        }
        long id = ids.getFirst();
        jdbc.sql("""
            INSERT INTO order_items(order_id,product_id,quantity,unit_price)
            SELECT ?,id,?,price FROM products WHERE id=?
            """).params(id, request.getQuantity(), request.getProductId()).update();
        return new Claim(id, true);
    }

    private Claim replay(CreateOrderRequest request, com.hthien.flash_sale.entity.Order order) {
        if ((request.getLockMode() != null && !order.getLockMode().name().equalsIgnoreCase(request.getLockMode()))
                || !order.getProduct().getId().equals(request.getProductId()) || order.getItems().size() != 1
                || !order.getItems().getFirst().getQuantity().equals(request.getQuantity()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Idempotency-Key already used with another payload");
        return new Claim(order.getId(), false);
    }

    public OrderResponse get(long id) {
        return tx.execute(s -> OrderResponse.from(orders.findById(id).orElseThrow(
            () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found"))));
    }

    private void finish(long id, State state, boolean cancelling) {
        OrderStatus terminal;
        if (state == State.RESERVED && !cancelling) terminal = OrderStatus.CONFIRMED;
        else if (state == State.REJECTED || state == State.RELEASED) terminal = OrderStatus.REJECTED;
        else return;
        String reason = state == State.RELEASED ? "CANCELLED" : "RESERVATION_REJECTED";
        jdbc.sql("""
            UPDATE orders SET order_status=?, completed_at=now(),
                latency_ms=(extract(epoch FROM now()-requested_at)*1000)::bigint,
                error_message=CASE WHEN ? THEN NULL ELSE ? END
            WHERE id=? AND order_status='PENDING' AND cancel_requested=?
            """).params(terminal.name(), terminal == OrderStatus.CONFIRMED, reason, id, cancelling).update();
    }

    @Scheduled(fixedDelayString = "${orders.reconcile-delay-ms:1000}")
    public void reconcile() {
        List<Long> ids = jdbc.sql("""
            SELECT id FROM orders WHERE order_status='PENDING' AND next_attempt_at <= now()
              AND requested_at <= now() - ? * interval '1 millisecond'
            ORDER BY next_attempt_at,id LIMIT 32
            """).param(reconcileAge).query(Long.class).list();
        for (long id : ids) reconcile(id);
    }

    private void reconcile(long id) {
        // Autocommit the cancellation fence before any remote side effect.
        var flags = jdbc.sql("""
            UPDATE orders SET cancel_requested=cancel_requested OR requested_at <= now() - ? * interval '1 millisecond',
                next_attempt_at=now() + ? * interval '1 millisecond'
            WHERE id=? AND order_status='PENDING' AND next_attempt_at<=now() RETURNING cancel_requested
            """).params(cancelAge, Math.max(1, reconcileAge), id).query(Boolean.class).list();
        if (flags.isEmpty()) return;
        var item = get(id).getItems().getFirst();
        var command = new Command(id, item.getProductId(), item.getQuantity());
        boolean cancelling = flags.getFirst();
        State state = cancelling ? inventory.release(command) : inventory.query(command);
        if (!cancelling && state == State.MISSING) state = inventory.reserve(command);
        finish(id, state, cancelling);
    }
}
