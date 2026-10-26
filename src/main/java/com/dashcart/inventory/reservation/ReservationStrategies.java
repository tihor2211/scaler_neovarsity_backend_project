package com.dashcart.inventory.reservation;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

// Holds every strategy and which one checkout uses now. The active one can be switched at runtime
// (PUT /admin/checkout/strategy) so the load tests can compare them without restarting the app.
@Component
public class ReservationStrategies {

    private final Map<ReservationStrategyType, StockReservationStrategy> byType =
            new EnumMap<>(ReservationStrategyType.class);
    private final AtomicReference<ReservationStrategyType> active;
    private final RedisStockStore redisStock;

    public ReservationStrategies(List<StockReservationStrategy> strategies, RedisStockStore redisStock,
            @Value("${dashcart.checkout.strategy:CONDITIONAL_UPDATE}") ReservationStrategyType initial) {
        strategies.forEach(strategy -> byType.put(strategy.type(), strategy));
        this.redisStock = redisStock;
        this.active = new AtomicReference<>(initial);
    }

    public StockReservationStrategy active() {
        return byType.get(active.get());
    }

    public ReservationStrategyType activeType() {
        return active.get();
    }

    // Switching to REDIS first copies current stock into Redis, so Redis starts in step with the database.
    public void activate(ReservationStrategyType type) {
        if (type == ReservationStrategyType.REDIS) {
            redisStock.loadAllFromDatabase();
        }
        active.set(type);
    }

    @EventListener(ApplicationReadyEvent.class)
    void loadRedisOnStartup() {
        if (active.get() == ReservationStrategyType.REDIS) {
            redisStock.loadAllFromDatabase();
        }
    }
}
