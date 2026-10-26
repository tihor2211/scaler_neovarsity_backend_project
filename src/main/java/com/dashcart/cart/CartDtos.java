package com.dashcart.cart;

import java.math.BigDecimal;
import java.util.List;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public final class CartDtos {

    private CartDtos() {
    }

    // quantity 0 removes the item.
    public record SetItemRequest(@NotNull Long storeId, @NotNull @Min(0) Integer quantity) {
    }

    public record CartLine(Long productId, String name, String unit, BigDecimal mrp, BigDecimal price, int quantity,
            int availableQty, boolean active) {

        public BigDecimal lineTotal() {
            return price.multiply(BigDecimal.valueOf(quantity));
        }
    }

    public record CartItemResponse(Long productId, String name, String unit, BigDecimal mrp, BigDecimal price,
            int quantity, BigDecimal lineTotal, boolean available) {
    }

    // available = the store can currently fulfil this line. Checkout re-checks under concurrency control.
    public record CartResponse(Long storeId, List<CartItemResponse> items, int itemCount, BigDecimal totalAmount,
            BigDecimal savings) {

        public static CartResponse empty() {
            return new CartResponse(null, List.of(), 0, BigDecimal.ZERO, BigDecimal.ZERO);
        }
    }
}
