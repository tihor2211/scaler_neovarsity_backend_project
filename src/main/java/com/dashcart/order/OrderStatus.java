package com.dashcart.order;

import java.util.EnumSet;
import java.util.Set;

//  CREATED --(payment started)--> PAYMENT_PENDING --(payment captured)--> PAID --> PACKED
//     |                                |                                           |
//     +--------(10 min, unpaid)--------+--> EXPIRED                    OUT_FOR_DELIVERY --> DELIVERED
//     +--------(customer cancels)------+--> CANCELLED
public enum OrderStatus {
    CREATED,
    PAYMENT_PENDING,
    PAID,
    PACKED,
    OUT_FOR_DELIVERY,
    DELIVERED,
    CANCELLED,
    EXPIRED;

    // Orders in these states hold reserved stock.
    public static final Set<OrderStatus> HOLDING_STOCK = EnumSet.of(CREATED, PAYMENT_PENDING);

    // The next fulfilment step an admin can move a paid order to; null when there isn't one.
    public OrderStatus nextFulfilmentStep() {
        return switch (this) {
            case PAID -> PACKED;
            case PACKED -> OUT_FOR_DELIVERY;
            case OUT_FOR_DELIVERY -> DELIVERED;
            default -> null;
        };
    }
}
