package com.dashcart.inventory.reservation;

import java.util.List;

// Moves stock from available to reserved for every item, or for none of them.
// Called inside the checkout transaction; if anything later fails, the transaction rollback undoes the
// database changes. Items arrive sorted by productId, so concurrent checkouts lock rows in the same
// order and can't deadlock.
public interface StockReservationStrategy {

    ReservationStrategyType type();

    // Throws InsufficientStockException if an item doesn't have enough stock,
    // or ReservationConflictException if the caller should retry in a new transaction.
    void reserve(long storeId, List<StockRequest> items);
}
