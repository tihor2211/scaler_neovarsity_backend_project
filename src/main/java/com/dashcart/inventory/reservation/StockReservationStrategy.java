package com.dashcart.inventory.reservation;

import java.util.List;

// Moves stock from available to reserved for every item, or for none of them.
//
// Checkout calls the two methods at different points of one transaction ("lock late"):
//   precheck  - first, before the order rows are written. Must not lock the stock rows. Lets buyers who
//               arrive after a sell-out be turned away before doing any writes.
//   reserve   - last, right before commit. The step that decides, under each strategy's concurrency
//               control. Doing it last means a hot product's row lock is held only until the commit
//               that immediately follows, instead of across the whole checkout.
// If anything fails, the transaction rollback undoes the database changes. Items arrive sorted by
// productId, so concurrent checkouts lock rows in the same order and can't deadlock.
public interface StockReservationStrategy {

    ReservationStrategyType type();

    // Throws InsufficientStockException to reject early. Must not take row locks.
    void precheck(long storeId, List<StockRequest> items);

    // Throws InsufficientStockException if an item doesn't have enough stock,
    // or ReservationConflictException if the caller should retry in a new transaction.
    void reserve(long storeId, List<StockRequest> items);
}
