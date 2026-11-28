package com.hthien.inventory.service;

import static com.hthien.inventory.model.Reservation.Status.REJECTED;
import static com.hthien.inventory.model.Reservation.Status.RELEASED;
import static com.hthien.inventory.model.Reservation.Status.RESERVED;

import com.hthien.inventory.concurrency.InventoryLockStrategy;
import com.hthien.inventory.model.Inventory;
import com.hthien.inventory.model.Reservation;
import com.hthien.inventory.repository.InventoryRepository;
import com.hthien.inventory.repository.ReservationRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ReservationService {
    private final InventoryRepository inventory;
    private final ReservationRepository reservations;
    private final InventoryLockStrategy strategy;
    private final TransactionTemplate transaction;

    ReservationService(InventoryRepository inventory, ReservationRepository reservations,
                       InventoryLockStrategy strategy, PlatformTransactionManager transactionManager) {
        this.inventory = inventory;
        this.reservations = reservations;
        this.strategy = strategy;
        this.transaction = new TransactionTemplate(transactionManager);
        this.transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    public Reservation reserve(long orderId, long productId, int quantity) {
        return strategy.around(productId, () -> transaction.execute(tx -> {
            if (!reservations.insert(orderId, productId, quantity, RESERVED)) {
                return checkPayload(reservations.find(orderId).orElseThrow(), productId, quantity);
            }
            if (strategy.deduct(productId, quantity)) {
                return new Reservation(orderId, productId, quantity, RESERVED);
            }
            reservations.updateStatus(orderId, REJECTED);
            return new Reservation(orderId, productId, quantity, REJECTED);
        }));
    }

    public Reservation release(long orderId, long productId, int quantity) {
        return strategy.around(productId, () -> transaction.execute(tx -> {
            if (reservations.insert(orderId, productId, quantity, RELEASED)) {
                return new Reservation(orderId, productId, quantity, RELEASED);
            }
            var existing = checkPayload(reservations.lock(orderId), productId, quantity);
            if (existing.status() != RESERVED) {
                return existing;
            }
            inventory.restore(productId, quantity);
            reservations.updateStatus(orderId, RELEASED);
            return new Reservation(orderId, productId, quantity, RELEASED);
        }));
    }

    public Reservation getReservation(long orderId) {
        return reservations.find(orderId).orElseThrow(
            () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Reservation not found"));
    }

    public Inventory getInventory(long productId) {
        return inventory.find(productId).orElseThrow(
            () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Inventory not found"));
    }

    public Inventory initialize(long productId, int stock) {
        return transaction.execute(tx -> {
            if (!inventory.initialize(productId, stock) && !inventory.matchesInitialStock(productId, stock)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Different or unmanaged initial stock");
            }
            return getInventory(productId);
        });
    }

    public Inventory setStock(long productId, int stock) {
        return strategy.around(productId, () -> transaction.execute(tx -> {
            if (!inventory.createIfAbsent(productId, stock)) {
                // Hold the stock row while checking the space needed for future releases.
                inventory.lock(productId).orElseThrow();
                if (reservations.reservedQuantity(productId) > Integer.MAX_VALUE - stock) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "Stock reset would prevent outstanding releases");
                }
                inventory.updateStock(productId, stock);
            }
            return getInventory(productId);
        }));
    }

    private static Reservation checkPayload(Reservation existing, long productId, int quantity) {
        if (existing.productId() != productId || existing.quantity() != quantity) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Order ID already has a different reservation payload");
        }
        return existing;
    }
}
