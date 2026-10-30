package com.dashcart.order;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class OrderStatusTest {

    @Test
    void paidOrdersMoveThroughFulfilmentInOrder() {
        assertThat(OrderStatus.PAID.nextFulfilmentStep()).isEqualTo(OrderStatus.PACKED);
        assertThat(OrderStatus.PACKED.nextFulfilmentStep()).isEqualTo(OrderStatus.OUT_FOR_DELIVERY);
        assertThat(OrderStatus.OUT_FOR_DELIVERY.nextFulfilmentStep()).isEqualTo(OrderStatus.DELIVERED);
    }

    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, names = {"CREATED", "PAYMENT_PENDING", "DELIVERED", "CANCELLED", "EXPIRED"})
    void unpaidAndFinishedOrdersHaveNoFulfilmentStep(OrderStatus status) {
        assertThat(status.nextFulfilmentStep()).isNull();
    }

    @Test
    void onlyUnpaidOrdersHoldStock() {
        assertThat(OrderStatus.HOLDING_STOCK).containsExactlyInAnyOrder(OrderStatus.CREATED, OrderStatus.PAYMENT_PENDING);
    }
}
