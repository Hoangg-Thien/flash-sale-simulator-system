package com.hthien.inventory.controller;

import com.hthien.inventory.model.Inventory;
import com.hthien.inventory.model.Reservation;
import com.hthien.inventory.service.ReservationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/v1")
class InventoryController {
    private final ReservationService service;

    InventoryController(ReservationService service) {
        this.service = service;
    }

    @PostMapping("/reservations")
    Reservation reserve(@RequestBody @Valid ReserveRequest request) {
        return service.reserve(request.orderId(), request.productId(), request.quantity());
    }

    @GetMapping("/reservations/{orderId}")
    Reservation getReservation(@PathVariable @Positive long orderId) {
        return service.getReservation(orderId);
    }

    @PostMapping("/reservations/{orderId}/release")
    Reservation release(@PathVariable @Positive long orderId, @RequestBody @Valid ReleaseRequest request) {
        return service.release(orderId, request.productId(), request.quantity());
    }

    @GetMapping("/inventory/{productId}")
    Inventory getInventory(@PathVariable @Positive long productId) {
        return service.getInventory(productId);
    }

    @PutMapping("/inventory/{productId}")
    Inventory setStock(@PathVariable @Positive long productId, @RequestBody @Valid StockRequest request) {
        return service.setStock(productId, request.stock());
    }

    record ReserveRequest(@NotNull @Positive Long orderId, @NotNull @Positive Long productId,
                          @NotNull @Positive Integer quantity) {}

    record ReleaseRequest(@NotNull @Positive Long productId, @NotNull @Positive Integer quantity) {}

    record StockRequest(@NotNull @PositiveOrZero Integer stock) {}
}
