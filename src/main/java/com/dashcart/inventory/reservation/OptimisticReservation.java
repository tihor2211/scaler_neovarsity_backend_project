package com.dashcart.inventory.reservation;

import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

// Optimistic locking: no lock while reading; the write succeeds only if the row's version is still the
// one we read. If someone else got there first, zero rows match and the whole checkout is retried in a
// fresh transaction (a retry inside the same transaction would re-read the same MySQL snapshot).
// Cheap when conflicts are rare; under a rush on one product most attempts conflict and are retried.
@Component
class OptimisticReservation implements StockReservationStrategy {

    private final JdbcTemplate jdbc;
    private final StockPrecheck precheck;

    OptimisticReservation(JdbcTemplate jdbc, StockPrecheck precheck) {
        this.jdbc = jdbc;
        this.precheck = precheck;
    }

    @Override
    public ReservationStrategyType type() {
        return ReservationStrategyType.OPTIMISTIC;
    }

    @Override
    public void precheck(long storeId, List<StockRequest> items) {
        precheck.requireAvailable(storeId, items);
    }

    @Override
    public void reserve(long storeId, List<StockRequest> items) {
        for (StockRequest item : items) {
            long[] row = jdbc.query(
                    "SELECT available_qty, version FROM store_inventory WHERE store_id = ? AND product_id = ?",
                    rs -> rs.next() ? new long[] {rs.getInt(1), rs.getLong(2)} : null, storeId, item.productId());
            if (row == null || row[0] < item.quantity()) {
                throw new InsufficientStockException(item);
            }
            int updated = jdbc.update("""
                    UPDATE store_inventory
                       SET available_qty = ?, reserved_qty = reserved_qty + ?, version = version + 1
                     WHERE store_id = ? AND product_id = ? AND version = ?""",
                    row[0] - item.quantity(), item.quantity(), storeId, item.productId(), row[1]);
            if (updated == 0) {
                throw new ReservationConflictException(item.productId());
            }
        }
    }
}
