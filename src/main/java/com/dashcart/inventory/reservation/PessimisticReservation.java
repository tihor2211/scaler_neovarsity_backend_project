package com.dashcart.inventory.reservation;

import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

// Pessimistic locking: SELECT ... FOR UPDATE takes an exclusive row lock and reads the latest committed
// value. Other checkouts for the same product wait on the lock until this transaction commits, so the
// check and the write can't interleave. Correct, but buyers of a hot product are served one at a time
// and each holds the lock for the whole checkout transaction.
@Component
class PessimisticReservation implements StockReservationStrategy {

    private final JdbcTemplate jdbc;

    PessimisticReservation(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public ReservationStrategyType type() {
        return ReservationStrategyType.PESSIMISTIC;
    }

    @Override
    public void reserve(long storeId, List<StockRequest> items) {
        for (StockRequest item : items) {
            Integer available = jdbc.query(
                    "SELECT available_qty FROM store_inventory WHERE store_id = ? AND product_id = ? FOR UPDATE",
                    rs -> rs.next() ? rs.getInt(1) : null, storeId, item.productId());
            if (available == null || available < item.quantity()) {
                throw new InsufficientStockException(item);
            }
            jdbc.update("""
                    UPDATE store_inventory
                       SET available_qty = available_qty - ?, reserved_qty = reserved_qty + ?, version = version + 1
                     WHERE store_id = ? AND product_id = ?""",
                    item.quantity(), item.quantity(), storeId, item.productId());
        }
    }
}
