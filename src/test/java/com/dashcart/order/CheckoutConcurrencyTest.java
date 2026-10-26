package com.dashcart.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

import com.dashcart.inventory.reservation.ReservationStrategyType;
import com.dashcart.support.IntegrationTest;
import com.dashcart.support.TestApi;

// A rush on one product: 40 buyers, each with 1 unit in their cart, check out at the same instant while
// only 15 units exist. Run once per strategy. The safe strategies must sell exactly 15 and never more;
// NAIVE is only reported, because whether it oversells in a given run depends on timing.
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CheckoutConcurrencyTest extends IntegrationTest {

    private static final int BUYERS = 40;
    private static final int STOCK = 15;

    @Autowired
    JdbcTemplate jdbc;

    TestApi api;
    String admin;
    String pincode;
    long storeId;
    long category;
    final List<String> buyers = new ArrayList<>();

    @BeforeAll
    void createBuyers() throws Exception {
        api = api();
        admin = api.adminToken();
        pincode = TestApi.randomPincode();
        storeId = api.createStore(admin, pincode);
        category = api.createCategory(admin);
        for (int i = 0; i < BUYERS; i++) {
            buyers.add(api.newCustomerToken());
        }
    }

    @AfterAll
    void resetStrategy() throws Exception {
        api.call(put("/admin/checkout/strategy"), admin, "{\"strategy\":\"PESSIMISTIC\"}", status().isOk());
    }

    @ParameterizedTest
    @EnumSource(ReservationStrategyType.class)
    void rushOnLastUnits(ReservationStrategyType strategy) throws Exception {
        api.call(put("/admin/checkout/strategy"), admin, "{\"strategy\":\"" + strategy + "\"}", status().isOk());
        long product = api.createProduct(admin, category, "Flash sale " + strategy, "999.00", "499.00");
        api.setStock(admin, storeId, product, STOCK);
        for (String buyer : buyers) {
            api.call(delete("/cart"), buyer, null, status().isNoContent());
            api.call(put("/cart/items/" + product), buyer, """
                    {"storeId":%d,"quantity":1}""".formatted(storeId), status().isOk());
        }

        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(BUYERS)) {
            for (String buyer : buyers) {
                results.add(pool.submit(() -> {
                    start.await();
                    return mvc.perform(post("/orders/checkout")
                                    .header("Authorization", "Bearer " + buyer)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("""
                                            {"deliveryAddress":"1 Rush Lane","deliveryPincode":"%s"}"""
                                            .formatted(pincode)))
                            .andReturn().getResponse().getStatus();
                }));
            }
            start.countDown();
        }

        int created = 0;
        int rejected = 0;
        for (Future<Integer> result : results) {
            int code = result.get();
            if (code == 201) {
                created++;
            } else if (code == 409) {
                rejected++;
            }
        }
        int ordered = jdbc.queryForObject("""
                SELECT COALESCE(SUM(oi.quantity), 0) FROM order_items oi JOIN orders o ON o.id = oi.order_id
                 WHERE oi.product_id = ? AND o.status = 'CREATED'""", Integer.class, product);
        int[] stock = jdbc.queryForObject(
                "SELECT available_qty, reserved_qty FROM store_inventory WHERE store_id = ? AND product_id = ?",
                (rs, n) -> new int[] {rs.getInt(1), rs.getInt(2)}, storeId, product);

        System.out.printf("%-18s created=%2d rejected=%2d unitsOrdered=%2d available=%2d reserved=%2d oversold=%d%n",
                strategy, created, rejected, ordered, stock[0], stock[1], Math.max(0, ordered - STOCK));

        assertThat(created + rejected).as("every request got 201 or 409").isEqualTo(BUYERS);
        assertThat(ordered).isEqualTo(created);
        if (strategy == ReservationStrategyType.NAIVE) {
            return;
        }
        assertThat(ordered).as("never sells more than it has").isLessThanOrEqualTo(STOCK);
        assertThat(stock[0] + stock[1]).as("available + reserved still adds up to the stock").isEqualTo(STOCK);
        assertThat(stock[1]).isEqualTo(ordered);
        if (strategy != ReservationStrategyType.OPTIMISTIC) {
            // Optimistic may give up after its retries even while stock is left; the others sell out exactly.
            assertThat(created).as("sells every unit").isEqualTo(STOCK);
        }
    }
}
