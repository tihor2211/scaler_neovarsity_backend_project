package com.dashcart.payment;

public enum PaymentStatus {
    // Razorpay order created, customer hasn't paid yet.
    CREATED,
    // Money received and the order is PAID.
    CAPTURED,
    // The attempt failed (card declined etc.); the customer can try again.
    FAILED,
    // Money arrived after the order had expired or been cancelled: its stock was already released,
    // so the payment has to be refunded.
    REFUND_REQUIRED
}
