package com.dashcart.inventory.reservation;

// Optimistic strategy only: someone changed the stock row between our read and our write.
// Not an error for the customer; CheckoutService retries the whole checkout in a new transaction.
public class ReservationConflictException extends RuntimeException {

    public ReservationConflictException(Long productId) {
        super("Stock for product " + productId + " changed concurrently", null, false, false);
    }
}
