package com.dashcart.inventory.reservation;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

// Redis is the gatekeeper: in precheck, an atomic Lua script decides who gets the stock, in memory,
// without touching MySQL's stock rows. Buyers who lose are turned away there. Winners write their order,
// then reserve updates MySQL with a conditional UPDATE as a safety net.
// If the checkout transaction doesn't commit, the Redis decrement is given back.
@Component
class RedisReservation implements StockReservationStrategy {

    private static final Logger log = LoggerFactory.getLogger(RedisReservation.class);

    private final RedisStockStore redisStock;
    private final JdbcTemplate jdbc;

    RedisReservation(RedisStockStore redisStock, JdbcTemplate jdbc) {
        this.redisStock = redisStock;
        this.jdbc = jdbc;
    }

    @Override
    public ReservationStrategyType type() {
        return ReservationStrategyType.REDIS;
    }

    @Override
    public void precheck(long storeId, List<StockRequest> items) {
        long result = redisStock.tryReserve(storeId, items);
        // A missing key (Redis restarted or the value was invalidated): copy that item's value from the
        // database and try again. A plain read may be slightly stale; that's safe, because reserve's
        // conditional UPDATE has the final say. At most one load per item.
        for (int loads = 0; result < 0 && loads < items.size(); loads++) {
            StockRequest missing = items.get((int) -result - 1);
            Integer available = jdbc.query(
                    "SELECT available_qty FROM store_inventory WHERE store_id = ? AND product_id = ?",
                    rs -> rs.next() ? rs.getInt(1) : null, storeId, missing.productId());
            if (available == null) {
                throw new InsufficientStockException(missing);
            }
            redisStock.setIfAbsent(storeId, missing.productId(), available);
            result = redisStock.tryReserve(storeId, items);
        }
        if (result != 0) {
            throw new InsufficientStockException(items.get((int) Math.abs(result) - 1));
        }

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) {
                    redisStock.release(storeId, items);
                }
            }
        });
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
                // Redis thought there was stock but the database disagrees: Redis is out of date. After the
                // rollback, drop the Redis value so the next checkout reloads it from the database.
                log.warn("Redis stock for store {} product {} was ahead of the database; reloading", storeId,
                        item.productId());
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCompletion(int status) {
                        redisStock.delete(storeId, item.productId());
                    }
                });
                throw new InsufficientStockException(item);
            }
        }
    }
}
