package com.dashcart.catalog;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public final class CatalogDtos {

    private CatalogDtos() {
    }

    public record CategoryRequest(@NotBlank @Size(max = 100) String name) {
    }

    public record CategoryResponse(Long id, String name) {

        static CategoryResponse from(Category category) {
            return new CategoryResponse(category.getId(), category.getName());
        }
    }

    public record ProductRequest(
            @NotNull Long categoryId,
            @NotBlank @Size(max = 200) String name,
            @Size(max = 2000) String description,
            @NotBlank @Size(max = 30) String unit,
            @NotNull @DecimalMin("0.01") @Digits(integer = 8, fraction = 2) BigDecimal mrp,
            @NotNull @DecimalMin("0.01") @Digits(integer = 8, fraction = 2) BigDecimal price,
            @Size(max = 500) String imageUrl,
            Boolean active) {
    }

    public record ProductResponse(Long id, Long categoryId, String name, String description, String unit,
            BigDecimal mrp, BigDecimal price, String imageUrl, boolean active) {

        static ProductResponse from(Product p) {
            return new ProductResponse(p.getId(), p.getCategoryId(), p.getName(), p.getDescription(), p.getUnit(),
                    p.getMrp(), p.getPrice(), p.getImageUrl(), p.isActive());
        }
    }

    // A product as one store sells it. Customers see whether it's in stock, not the exact count.
    public record StoreProductView(Long productId, String name, String description, String unit, BigDecimal mrp,
            BigDecimal price, String imageUrl, Long categoryId, String categoryName, int availableQty) {
    }

    public record StoreProductResponse(Long productId, String name, String description, String unit,
            BigDecimal mrp, BigDecimal price, String imageUrl, Long categoryId, String categoryName,
            boolean inStock) {

        static StoreProductResponse from(StoreProductView v) {
            return new StoreProductResponse(v.productId(), v.name(), v.description(), v.unit(), v.mrp(), v.price(),
                    v.imageUrl(), v.categoryId(), v.categoryName(), v.availableQty() > 0);
        }
    }
}
