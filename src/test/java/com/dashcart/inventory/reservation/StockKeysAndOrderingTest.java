package com.dashcart.inventory.reservation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class StockKeysAndOrderingTest {

    @Test
    void redisStockKeysCarryTheStoreAsAClusterHashTag() {
        // Only the part in braces picks the Redis Cluster slot, so one store's keys stay together and a
        // multi-item Lua reservation is allowed.
        assertThat(RedisStockStore.key(3, 15)).isEqualTo("stock:{3}:15");
        assertThat(RedisStockStore.key(3, 16)).startsWith("stock:{3}:");
    }

    @Test
    void stockIsConfirmedAndReleasedInProductIdOrder() {
        List<StockRequest> items = List.of(new StockRequest(30L, 1, "Rice"), new StockRequest(9L, 2, "Bread"),
                new StockRequest(15L, 1, "Dal"));

        assertThat(StockLedger.sorted(items)).extracting(StockRequest::productId).containsExactly(9L, 15L, 30L);
    }

    @Test
    void insufficientStockMessageNamesTheProduct() {
        assertThat(new InsufficientStockException(new StockRequest(15L, 2, "Toor Dal")))
                .hasMessage("Not enough Toor Dal left for this order");
    }
}
