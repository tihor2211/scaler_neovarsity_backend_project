package com.dashcart.cart;

import java.io.Serializable;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

@Embeddable
public record CartItemId(
        @Column(name = "cart_id") Long cartId,
        @Column(name = "product_id") Long productId) implements Serializable {
}
