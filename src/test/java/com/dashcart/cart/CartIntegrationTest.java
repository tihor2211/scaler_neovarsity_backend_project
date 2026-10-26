package com.dashcart.cart;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.dashcart.support.IntegrationTest;
import com.dashcart.support.TestApi;

class CartIntegrationTest extends IntegrationTest {

    TestApi api;
    String admin;
    long storeId;
    long milk;
    long bread;

    @BeforeEach
    void setUp() throws Exception {
        api = api();
        admin = api.adminToken();
        storeId = api.createStore(admin, TestApi.randomPincode());
        long category = api.createCategory(admin);
        milk = api.createProduct(admin, category, "Milk", "30.00", "28.00");
        bread = api.createProduct(admin, category, "Bread", "55.00", "50.00");
        api.setStock(admin, storeId, milk, 5);
        api.setStock(admin, storeId, bread, 20);
    }

    @Test
    void addUpdateRemoveAndTotals() throws Exception {
        String customer = api.newCustomerToken();
        setItem(customer, milk, storeId, 2);
        String cart = setItem(customer, bread, storeId, 1);

        // 2 x 28 + 1 x 50 = 106; savings 2 x 2 + 1 x 5 = 9
        mvc.perform(get("/cart").header("Authorization", "Bearer " + customer))
                .andExpect(jsonPath("$.storeId", is((int) storeId)))
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.itemCount", is(3)))
                .andExpect(jsonPath("$.totalAmount", is(106.0)))
                .andExpect(jsonPath("$.savings", is(9.0)));

        setItem(customer, milk, storeId, 0);
        mvc.perform(get("/cart").header("Authorization", "Bearer " + customer))
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.totalAmount", is(50.0)));

        mvc.perform(delete("/cart").header("Authorization", "Bearer " + customer)).andExpect(status().isNoContent());
        mvc.perform(get("/cart").header("Authorization", "Bearer " + customer))
                .andExpect(jsonPath("$.items", hasSize(0)));
    }

    @Test
    void cannotAddMoreThanInStockOrPerItemLimit() throws Exception {
        String customer = api.newCustomerToken();
        api.call(put("/cart/items/" + milk), customer, body(storeId, 6), status().isConflict());
        api.call(put("/cart/items/" + bread), customer, body(storeId, 11), status().isBadRequest());
    }

    @Test
    void cartIsTiedToOneStore() throws Exception {
        String customer = api.newCustomerToken();
        long otherStore = api.createStore(admin, TestApi.randomPincode());
        api.setStock(admin, otherStore, milk, 10);

        setItem(customer, milk, storeId, 1);
        api.call(put("/cart/items/" + milk), customer, body(otherStore, 1), status().isConflict());

        // After clearing, the cart can move to the other store.
        mvc.perform(delete("/cart").header("Authorization", "Bearer " + customer));
        setItem(customer, milk, otherStore, 1);
    }

    @Test
    void productNotSoldAtStoreIsRejected() throws Exception {
        String customer = api.newCustomerToken();
        long category = api.createCategory(admin);
        long notStocked = api.createProduct(admin, category, "Elsewhere", "10.00", "9.00");
        api.call(put("/cart/items/" + notStocked), customer, body(storeId, 1), status().isNotFound());
    }

    @Test
    void cartNeedsLogin() throws Exception {
        mvc.perform(get("/cart")).andExpect(status().isUnauthorized());
    }

    private String setItem(String token, long productId, long store, int quantity) throws Exception {
        return api.call(put("/cart/items/" + productId), token, body(store, quantity), status().isOk());
    }

    private static String body(long store, int quantity) {
        return """
                {"storeId":%d,"quantity":%d}""".formatted(store, quantity);
    }
}
