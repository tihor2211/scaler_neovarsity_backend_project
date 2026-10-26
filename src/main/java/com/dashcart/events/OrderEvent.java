package com.dashcart.events;

import java.math.BigDecimal;
import java.time.Instant;

// Published to the order-events topic whenever an order changes status. Keyed by order id, so all
// events of one order land in the same partition and are consumed in order.
public record OrderEvent(Long orderId, Long userId, Long storeId, String status, BigDecimal totalAmount,
        Instant occurredAt) {
}
