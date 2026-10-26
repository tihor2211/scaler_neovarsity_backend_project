package com.dashcart.catalog;

import java.math.BigDecimal;
import java.time.Instant;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

// A product in the shared catalogue. How many units each store has lives in StoreInventory.
@Entity
@Table(name = "products")
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "category_id", nullable = false)
    private Long categoryId;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    // Pack size shown to the customer, e.g. "500 g" or "1 L".
    @Column(nullable = false, length = 30)
    private String unit;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal mrp;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal price;

    @Column(name = "image_url", length = 500)
    private String imageUrl;

    @Column(nullable = false)
    private boolean active = true;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Product() {
    }

    public Product(Long categoryId, String name, String description, String unit, BigDecimal mrp, BigDecimal price,
            String imageUrl) {
        this.categoryId = categoryId;
        this.name = name;
        this.description = description;
        this.unit = unit;
        this.mrp = mrp;
        this.price = price;
        this.imageUrl = imageUrl;
    }

    public void update(Long categoryId, String name, String description, String unit, BigDecimal mrp,
            BigDecimal price, String imageUrl, boolean active) {
        this.categoryId = categoryId;
        this.name = name;
        this.description = description;
        this.unit = unit;
        this.mrp = mrp;
        this.price = price;
        this.imageUrl = imageUrl;
        this.active = active;
    }

    public Long getId() {
        return id;
    }

    public Long getCategoryId() {
        return categoryId;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public String getUnit() {
        return unit;
    }

    public BigDecimal getMrp() {
        return mrp;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public String getImageUrl() {
        return imageUrl;
    }

    public boolean isActive() {
        return active;
    }
}
