package com.dashcart.inventory;

import java.time.Instant;

import org.hibernate.annotations.UpdateTimestamp;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

// Stock of one product in one store.
// availableQty: can be sold now. reservedQty: held by orders that are waiting for payment.
// version: bumped on every stock change; the optimistic checkout strategy uses it to detect conflicts.
// Checkout never changes stock through this entity; the reservation strategies use explicit SQL so that
// each one's behaviour under concurrency is visible in one place.
@Entity
@Table(name = "store_inventory")
public class StoreInventory {

    @EmbeddedId
    private StoreInventoryId id;

    @Column(name = "available_qty", nullable = false)
    private int availableQty;

    @Column(name = "reserved_qty", nullable = false)
    private int reservedQty;

    @Column(nullable = false)
    private long version;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected StoreInventory() {
    }

    public StoreInventory(StoreInventoryId id, int availableQty) {
        this.id = id;
        this.availableQty = availableQty;
    }

    public void setAvailableQty(int availableQty) {
        this.availableQty = availableQty;
        this.version++;
    }

    public StoreInventoryId getId() {
        return id;
    }

    public int getAvailableQty() {
        return availableQty;
    }

    public int getReservedQty() {
        return reservedQty;
    }

    public long getVersion() {
        return version;
    }
}
