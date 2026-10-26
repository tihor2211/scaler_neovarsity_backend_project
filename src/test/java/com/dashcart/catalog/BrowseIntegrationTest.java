package com.dashcart.catalog;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import com.dashcart.support.IntegrationTest;
import com.dashcart.support.TestApi;

class BrowseIntegrationTest extends IntegrationTest {

    @Test
    void pincodeFindsItsStoreWithoutLogin() throws Exception {
        TestApi api = api();
        String pincode = TestApi.randomPincode();
        long storeId = api.createStore(api.adminToken(), pincode);

        mvc.perform(get("/stores/serviceable").param("pincode", pincode))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.storeId", is((int) storeId)));
    }

    @Test
    void unknownOrInvalidPincode() throws Exception {
        mvc.perform(get("/stores/serviceable").param("pincode", TestApi.UNSERVED_PINCODE))
                .andExpect(status().isNotFound());
        mvc.perform(get("/stores/serviceable").param("pincode", "12")).andExpect(status().isBadRequest());
    }

    @Test
    void storeListingShowsStockedProductsWithFilters() throws Exception {
        TestApi api = api();
        String admin = api.adminToken();
        long storeId = api.createStore(admin, TestApi.randomPincode());
        long fruits = api.createCategory(admin);
        long dairy = api.createCategory(admin);
        long apple = api.createProduct(admin, fruits, "Apple Shimla", "180.00", "149.00");
        long banana = api.createProduct(admin, fruits, "Banana Robusta", "60.00", "49.00");
        long milk = api.createProduct(admin, dairy, "Toned Milk", "30.00", "28.00");
        api.createProduct(admin, dairy, "Not Stocked Here", "10.00", "9.00");
        api.setStock(admin, storeId, apple, 12);
        api.setStock(admin, storeId, banana, 0);
        api.setStock(admin, storeId, milk, 40);

        // Only products this store stocks, sorted by name; out-of-stock still listed but flagged.
        mvc.perform(get("/stores/{id}/products", storeId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements", is(3)))
                .andExpect(jsonPath("$.content[*].name", contains("Apple Shimla", "Banana Robusta", "Toned Milk")))
                .andExpect(jsonPath("$.content[1].inStock", is(false)));

        mvc.perform(get("/stores/{id}/products", storeId).param("categoryId", String.valueOf(dairy)))
                .andExpect(jsonPath("$.content[*].name", contains("Toned Milk")));

        mvc.perform(get("/stores/{id}/products", storeId).param("q", "BAN"))
                .andExpect(jsonPath("$.content[*].name", contains("Banana Robusta")));

        mvc.perform(get("/stores/{id}/products", storeId).param("size", "2").param("page", "1"))
                .andExpect(jsonPath("$.totalPages", is(2)))
                .andExpect(jsonPath("$.content[*].name", contains("Toned Milk")));

        mvc.perform(get("/stores/{id}/products/{pid}", storeId, apple))
                .andExpect(jsonPath("$.price", is(149.0)))
                .andExpect(jsonPath("$.inStock", is(true)));
    }

    @Test
    void stockTakeClearsTheCachedListing() throws Exception {
        TestApi api = api();
        String admin = api.adminToken();
        long storeId = api.createStore(admin, TestApi.randomPincode());
        long product = api.createProduct(admin, api.createCategory(admin), "Paneer", "95.00", "89.00");
        api.setStock(admin, storeId, product, 3);

        mvc.perform(get("/stores/{id}/products", storeId)).andExpect(jsonPath("$.content[0].inStock", is(true)));
        api.setStock(admin, storeId, product, 0);
        mvc.perform(get("/stores/{id}/products", storeId)).andExpect(jsonPath("$.content[0].inStock", is(false)));
    }

    @Test
    void priceAboveMrpIsRejected() throws Exception {
        TestApi api = api();
        String admin = api.adminToken();
        long category = api.createCategory(admin);
        api.call(post("/admin/products"), admin, """
                {"categoryId":%d,"name":"Bad","unit":"1 pc","mrp":10.00,"price":12.00}""".formatted(category),
                status().isBadRequest());
    }

    @Test
    void customersCantUseAdminEndpoints() throws Exception {
        String customer = api().newCustomerToken();
        mvc.perform(post("/admin/categories").header("Authorization", "Bearer " + customer)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Hack\"}"))
                .andExpect(status().isForbidden());
    }
}
