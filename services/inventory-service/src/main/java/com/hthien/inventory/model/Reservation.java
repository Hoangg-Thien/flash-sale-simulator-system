package com.hthien.inventory.model;

public record Reservation(long orderId, long productId, int quantity, Status status) {
    public enum Status {
        RESERVED, REJECTED, RELEASED
    }
}
