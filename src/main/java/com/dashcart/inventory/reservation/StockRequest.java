package com.dashcart.inventory.reservation;

// One cart line to reserve. productName is only used in error messages.
public record StockRequest(Long productId, int quantity, String productName) {
}
