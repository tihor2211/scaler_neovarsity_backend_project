package com.dashcart.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.dashcart.support.IntegrationTest;
import com.dashcart.support.TestApi;
import com.jayway.jsonpath.JsonPath;

class CheckoutIntegrationTest extends IntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    TestApi api;
    String admin;
    String pincode;
    long storeId;
    long milk;
    long bread;

    @BeforeEach
    void setUp() throws Exception {
        api = api();
        admin = api.adminToken();
        pincode = TestApi.randomPincode();
        storeId = api.createStore(admin, pincode);
        long category = api.createCategory(admin);
        milk = api.createProduct(admin, category, "Milk", "30.00", "28.00");
        bread = api.createProduct(admin, category, "Bread", "55.00", "50.00");
        api.setStock(admin, storeId, milk, 10);
        api.setStock(admin, storeId, bread, 10);
    }

    @AfterEach
    void resetStrategy() throws Exception {
        api.call(put("/admin/checkout/strategy"), admin, "{\"strategy\":\"PESSIMISTIC\"}", status().isOk());
    }

    @Test
    void checkoutReservesStockCreatesOrderAndEmptiesCart() throws Exception {
        String customer = api.newCustomerToken();
        addToCart(customer, milk, 2);
        addToCart(customer, bread, 1);

        String order = checkout(customer, pincode, status().isCreated());
        assertThat((String) JsonPath.read(order, "$.status")).isEqualTo("CREATED");
        assertThat(((Number) JsonPath.read(order, "$.totalAmount")).doubleValue()).isEqualTo(106.0);
        assertThat((List<?>) JsonPath.read(order, "$.items")).hasSize(2);

        assertThat(stock(milk)).containsEntry("available", 8).containsEntry("reserved", 2);
        assertThat(stock(bread)).containsEntry("available", 9).containsEntry("reserved", 1);
        mvc.perform(get("/cart").header("Authorization", "Bearer " + customer))
                .andExpect(jsonPath("$.items", hasSize(0)));

        long orderId = ((Number) JsonPath.read(order, "$.id")).longValue();
        mvc.perform(get("/orders").header("Authorization", "Bearer " + customer))
                .andExpect(jsonPath("$.content[0].id", is((int) orderId)))
                .andExpect(jsonPath("$.content[0].items", hasSize(2)));
    }

    @Test
    void cancelReleasesStockOnce() throws Exception {
        String customer = api.newCustomerToken();
        addToCart(customer, milk, 3);
        long orderId = orderId(checkout(customer, pincode, status().isCreated()));

        api.call(post("/orders/" + orderId + "/cancel"), customer, null, status().isOk());
        assertThat(stock(milk)).containsEntry("available", 10).containsEntry("reserved", 0);

        api.call(post("/orders/" + orderId + "/cancel"), customer, null, status().isConflict());
        assertThat(stock(milk)).containsEntry("available", 10);
    }

    @Test
    void unpaidOrderExpiresAndReleasesStock() throws Exception {
        String customer = api.newCustomerToken();
        addToCart(customer, milk, 4);
        long orderId = orderId(checkout(customer, pincode, status().isCreated()));

        jdbc.update("UPDATE orders SET expires_at = NOW(6) - INTERVAL 1 MINUTE WHERE id = ?", orderId);
        api.call(post("/admin/orders/expire-due"), admin, null, status().isOk());

        mvc.perform(get("/orders/" + orderId).header("Authorization", "Bearer " + customer))
                .andExpect(jsonPath("$.status", is("EXPIRED")));
        assertThat(stock(milk)).containsEntry("available", 10).containsEntry("reserved", 0);
    }

    @Test
    void cannotCheckoutMoreThanIsLeft() throws Exception {
        String first = api.newCustomerToken();
        String second = api.newCustomerToken();
        addToCart(first, milk, 8);
        addToCart(second, milk, 5);
        checkout(first, pincode, status().isCreated());
        checkout(second, pincode, status().isConflict());
        assertThat(stock(milk)).containsEntry("available", 2).containsEntry("reserved", 8);
    }

    @Test
    void pincodeMustBeServedByTheCartsStore() throws Exception {
        String customer = api.newCustomerToken();
        addToCart(customer, milk, 1);
        String otherPincode = TestApi.randomPincode();
        api.createStore(admin, otherPincode);
        checkout(customer, otherPincode, status().isBadRequest());
        checkout(customer, TestApi.UNSERVED_PINCODE, status().isNotFound());
    }

    @Test
    void emptyCartCannotCheckOut() throws Exception {
        checkout(api.newCustomerToken(), pincode, status().isBadRequest());
    }

    @Test
    void redisStrategyKeepsRedisAndDatabaseInStep() throws Exception {
        api.call(put("/admin/checkout/strategy"), admin, "{\"strategy\":\"REDIS\"}", status().isOk());
        String customer = api.newCustomerToken();
        addToCart(customer, milk, 3);
        long orderId = orderId(checkout(customer, pincode, status().isCreated()));
        assertThat(stock(milk)).containsEntry("available", 7);
        assertThat(redisStock(milk)).isEqualTo("7");

        api.call(post("/orders/" + orderId + "/cancel"), customer, null, status().isOk());
        assertThat(stock(milk)).containsEntry("available", 10);
        assertThat(redisStock(milk)).isEqualTo("10");
    }

    private void addToCart(String token, long productId, int quantity) throws Exception {
        api.call(put("/cart/items/" + productId), token, """
                {"storeId":%d,"quantity":%d}""".formatted(storeId, quantity), status().isOk());
    }

    private String checkout(String token, String deliveryPincode,
            org.springframework.test.web.servlet.ResultMatcher expected) throws Exception {
        return api.call(post("/orders/checkout"), token, """
                {"deliveryAddress":"12 Test Street","deliveryPincode":"%s"}""".formatted(deliveryPincode), expected);
    }

    private static long orderId(String orderJson) {
        return ((Number) JsonPath.read(orderJson, "$.id")).longValue();
    }

    private Map<String, Integer> stock(long productId) {
        return jdbc.queryForObject(
                "SELECT available_qty, reserved_qty FROM store_inventory WHERE store_id = ? AND product_id = ?",
                (rs, n) -> Map.of("available", rs.getInt(1), "reserved", rs.getInt(2)), storeId, productId);
    }

    @Autowired
    org.springframework.data.redis.core.StringRedisTemplate redis;

    private String redisStock(long productId) {
        return redis.opsForValue().get("stock:{" + storeId + "}:" + productId);
    }
}
