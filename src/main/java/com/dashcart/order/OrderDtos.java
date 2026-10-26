package com.dashcart.order;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import com.dashcart.inventory.reservation.ReservationStrategyType;
import com.dashcart.store.StoreDtos;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class OrderDtos {

    private OrderDtos() {
    }

    public record CheckoutRequest(
            @NotBlank @Size(max = 500) String deliveryAddress,
            @NotNull @Pattern(regexp = StoreDtos.PINCODE_REGEX, message = "must be a 6-digit pincode")
            String deliveryPincode) {
    }

    public record OrderItemView(Long orderId, Long productId, String name, String unit, int quantity,
            BigDecimal unitPrice) {
    }

    public record OrderItemResponse(Long productId, String name, String unit, int quantity, BigDecimal unitPrice,
            BigDecimal lineTotal) {

        static OrderItemResponse from(OrderItemView v) {
            return new OrderItemResponse(v.productId(), v.name(), v.unit(), v.quantity(), v.unitPrice(),
                    v.unitPrice().multiply(BigDecimal.valueOf(v.quantity())));
        }
    }

    public record OrderResponse(Long id, OrderStatus status, Long storeId, BigDecimal totalAmount,
            String deliveryAddress, String deliveryPincode, ReservationStrategyType reservationStrategy,
            Instant expiresAt, Instant createdAt, List<OrderItemResponse> items) {

        static OrderResponse from(CustomerOrder o, List<OrderItemResponse> items) {
            return new OrderResponse(o.getId(), o.getStatus(), o.getStoreId(), o.getTotalAmount(),
                    o.getDeliveryAddress(), o.getDeliveryPincode(), o.getReservationStrategy(), o.getExpiresAt(),
                    o.getCreatedAt(), items);
        }
    }

    public record ExpireResponse(int expired) {
    }
}
