package com.dashcart.inventory.reservation;

import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

// The check and the decrement are one UPDATE statement. The database evaluates "available_qty >= ?"
// against the latest committed row while holding the row lock, so two buyers can't both take the last
// unit. One round trip per item instead of two (no separate SELECT). The row lock is still held until
// the checkout transaction commits.
@Component
class ConditionalUpdateReservation implements StockReservationStrategy {

    private final JdbcTemplate jdbc;

    ConditionalUpdateReservation(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public ReservationStrategyType type() {
        return ReservationStrategyType.CONDITIONAL_UPDATE;
    }

    @Override
    public void reserve(long storeId, List<StockRequest> items) {
        for (StockRequest item : items) {
            int updated = jdbc.update("""
                    UPDATE store_inventory
                       SET available_qty = available_qty - ?, reserved_qty = reserved_qty + ?, version = version + 1
                     WHERE store_id = ? AND product_id = ? AND available_qty >= ?""",
                    item.quantity(), item.quantity(), storeId, item.productId(), item.quantity());
            if (updated == 0) {
                throw new InsufficientStockException(item);
            }
        }
    }
}
