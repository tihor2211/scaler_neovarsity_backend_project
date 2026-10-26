package com.dashcart.inventory.reservation;

import java.util.Comparator;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

// What happens to reserved stock after checkout, whichever strategy reserved it:
//   confirm: payment arrived, the units are sold (reserved goes down, available stays).
//   release: order expired or was cancelled, the units go back on sale.
// Must be called inside a transaction.
@Component
public class StockLedger {

    private final JdbcTemplate jdbc;
    private final RedisStockStore redisStock;

    public StockLedger(JdbcTemplate jdbc, RedisStockStore redisStock) {
        this.jdbc = jdbc;
        this.redisStock = redisStock;
    }

    public void confirm(long storeId, List<StockRequest> items) {
        for (StockRequest item : sorted(items)) {
            jdbc.update("""
                    UPDATE store_inventory SET reserved_qty = reserved_qty - ?, version = version + 1
                     WHERE store_id = ? AND product_id = ?""",
                    item.quantity(), storeId, item.productId());
        }
    }

    public void release(long storeId, List<StockRequest> items, ReservationStrategyType reservedWith) {
        List<StockRequest> ordered = sorted(items);
        for (StockRequest item : ordered) {
            jdbc.update("""
                    UPDATE store_inventory
                       SET available_qty = available_qty + ?, reserved_qty = reserved_qty - ?, version = version + 1
                     WHERE store_id = ? AND product_id = ?""",
                    item.quantity(), item.quantity(), storeId, item.productId());
        }
        if (reservedWith == ReservationStrategyType.REDIS) {
            // Only after the database has committed, so Redis never shows stock the database hasn't freed.
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    redisStock.release(storeId, ordered);
                }
            });
        }
    }

    // Same lock order as checkout, to avoid deadlocks with concurrent checkouts.
    static List<StockRequest> sorted(List<StockRequest> items) {
        return items.stream().sorted(Comparator.comparing(StockRequest::productId)).toList();
    }
}
