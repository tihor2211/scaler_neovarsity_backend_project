package com.dashcart.inventory;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

public final class InventoryDtos {

    private InventoryDtos() {
    }

    // Stock-take: sets the sellable quantity of each product to the counted value.
    public record SetStockRequest(@NotEmpty List<@Valid StockLine> items) {
    }

    public record StockLine(@NotNull Long productId, @NotNull @Min(0) Integer availableQty) {
    }

    public record InventoryRow(Long productId, String productName, String unit, int availableQty, int reservedQty,
            long version) {
    }
}
