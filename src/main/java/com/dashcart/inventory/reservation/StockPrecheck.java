package com.dashcart.inventory.reservation;

import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

// Non-locking "is there obviously not enough?" check used by the database strategies' precheck.
// A plain read can be stale, so passing it proves nothing: reserve still decides. Failing it is safe,
// because stock that's gone in a recent snapshot doesn't come back during a rush.
@Component
class StockPrecheck {

    private final JdbcTemplate jdbc;

    StockPrecheck(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    void requireAvailable(long storeId, List<StockRequest> items) {
        for (StockRequest item : items) {
            Integer available = jdbc.query(
                    "SELECT available_qty FROM store_inventory WHERE store_id = ? AND product_id = ?",
                    rs -> rs.next() ? rs.getInt(1) : null, storeId, item.productId());
            if (available == null || available < item.quantity()) {
                throw new InsufficientStockException(item);
            }
        }
    }
}
