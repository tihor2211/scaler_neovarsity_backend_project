package com.dashcart.inventory;

import java.io.Serializable;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

@Embeddable
public record StoreInventoryId(
        @Column(name = "store_id") Long storeId,
        @Column(name = "product_id") Long productId) implements Serializable {
}
