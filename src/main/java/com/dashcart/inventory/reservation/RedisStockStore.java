package com.dashcart.inventory.reservation;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

// Mirror of available stock in Redis, used only by the REDIS strategy.
// Key: stock:{storeId}:productId. The {storeId} hash tag keeps one store's keys in the same Redis Cluster
// slot, which multi-key Lua scripts require.
@Component
public class RedisStockStore {

    // Returns 0 if every item had enough and all were decremented; i if item i (1-based) didn't have
    // enough (nothing decremented); -i if item i's key is missing (nothing decremented).
    // Redis runs a script without interleaving other commands, so check-then-decrement is atomic.
    private static final RedisScript<Long> RESERVE = RedisScript.of("""
            for i = 1, #KEYS do
                local stock = redis.call('GET', KEYS[i])
                if not stock then return -i end
                if tonumber(stock) < tonumber(ARGV[i]) then return i end
            end
            for i = 1, #KEYS do
                redis.call('DECRBY', KEYS[i], ARGV[i])
            end
            return 0
            """, Long.class);

    // Gives stock back. Skips missing keys: they'll be reloaded from the database, which is already correct.
    private static final RedisScript<Long> RELEASE = RedisScript.of("""
            for i = 1, #KEYS do
                if redis.call('EXISTS', KEYS[i]) == 1 then
                    redis.call('INCRBY', KEYS[i], ARGV[i])
                end
            end
            return 0
            """, Long.class);

    private final StringRedisTemplate redis;
    private final JdbcTemplate jdbc;

    public RedisStockStore(StringRedisTemplate redis, JdbcTemplate jdbc) {
        this.redis = redis;
        this.jdbc = jdbc;
    }

    static String key(long storeId, long productId) {
        return "stock:{" + storeId + "}:" + productId;
    }

    long tryReserve(long storeId, List<StockRequest> items) {
        return redis.execute(RESERVE, keys(storeId, items), quantities(items));
    }

    void release(long storeId, List<StockRequest> items) {
        redis.execute(RELEASE, keys(storeId, items), quantities(items));
    }

    void setIfAbsent(long storeId, long productId, int available) {
        redis.opsForValue().setIfAbsent(key(storeId, productId), String.valueOf(available));
    }

    public void delete(long storeId, long productId) {
        redis.delete(key(storeId, productId));
    }

    public Integer get(long storeId, long productId) {
        String value = redis.opsForValue().get(key(storeId, productId));
        return value == null ? null : Integer.valueOf(value);
    }

    // Copies every store's available stock from the database into Redis.
    public void loadAllFromDatabase() {
        Map<String, String> values = new HashMap<>();
        jdbc.query("SELECT store_id, product_id, available_qty FROM store_inventory",
                rs -> {
                    values.put(key(rs.getLong(1), rs.getLong(2)), String.valueOf(rs.getInt(3)));
                });
        if (!values.isEmpty()) {
            redis.opsForValue().multiSet(values);
        }
    }

    private static List<String> keys(long storeId, List<StockRequest> items) {
        return items.stream().map(item -> key(storeId, item.productId())).toList();
    }

    private static Object[] quantities(List<StockRequest> items) {
        return items.stream().map(item -> String.valueOf(item.quantity())).toArray();
    }
}
