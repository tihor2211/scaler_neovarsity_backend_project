package com.dashcart.inventory.reservation;

import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

// Deliberately unsafe baseline. Two concurrent checkouts can both read available = 1, both pass the
// check, and both write 0: the second write overwrites the first (a "lost update") and the store sells
// two units it only had one of. Kept to demonstrate the problem the other strategies solve.
@Component
class NaiveReservation implements StockReservationStrategy {

    private final JdbcTemplate jdbc;

    NaiveReservation(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public ReservationStrategyType type() {
        return ReservationStrategyType.NAIVE;
    }

    @Override
    public void reserve(long storeId, List<StockRequest> items) {
        for (StockRequest item : items) {
            Integer available = jdbc.query(
                    "SELECT available_qty FROM store_inventory WHERE store_id = ? AND product_id = ?",
                    rs -> rs.next() ? rs.getInt(1) : null, storeId, item.productId());
            if (available == null || available < item.quantity()) {
                throw new InsufficientStockException(item);
            }
            jdbc.update("""
                    UPDATE store_inventory
                       SET available_qty = ?, reserved_qty = reserved_qty + ?, version = version + 1
                     WHERE store_id = ? AND product_id = ?""",
                    available - item.quantity(), item.quantity(), storeId, item.productId());
        }
    }
}
