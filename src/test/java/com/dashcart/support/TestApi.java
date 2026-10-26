package com.dashcart.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.jayway.jsonpath.JsonPath;

// Small helpers that call the real API to set up test data.
public class TestApi {

    public static final String ADMIN_EMAIL = "admin@test.local";
    public static final String ADMIN_PASSWORD = "admin-password-123";

    private final MockMvc mvc;

    public TestApi(MockMvc mvc) {
        this.mvc = mvc;
    }

    public String adminToken() throws Exception {
        return login(ADMIN_EMAIL, ADMIN_PASSWORD);
    }

    public String newCustomerToken() throws Exception {
        String email = "user-" + UUID.randomUUID() + "@test.local";
        call(post("/auth/register"), null, """
                {"name":"Test Customer","email":"%s","password":"secret-pass-1"}""".formatted(email),
                status().isCreated());
        return login(email, "secret-pass-1");
    }

    public String login(String email, String password) throws Exception {
        String body = call(post("/auth/login"), null, """
                {"email":"%s","password":"%s"}""".formatted(email, password), status().isOk());
        return JsonPath.read(body, "$.accessToken");
    }

    // Creates an active store serving the given pincodes; returns its id.
    public long createStore(String adminToken, String... pincodes) throws Exception {
        String store = call(post("/admin/stores"), adminToken, """
                {"name":"Store %s","address":"1 Test Road","latitude":12.9166,"longitude":77.6101}"""
                .formatted(UUID.randomUUID()), status().isCreated());
        long storeId = ((Number) JsonPath.read(store, "$.id")).longValue();
        if (pincodes.length > 0) {
            String list = List.of(pincodes).stream().map(p -> "\"" + p + "\"").collect(Collectors.joining(","));
            call(put("/admin/stores/" + storeId + "/pincodes"), adminToken, "{\"pincodes\":[" + list + "]}",
                    status().isOk());
        }
        return storeId;
    }

    public long createCategory(String adminToken) throws Exception {
        String body = call(post("/admin/categories"), adminToken,
                "{\"name\":\"Category " + UUID.randomUUID() + "\"}", status().isCreated());
        return ((Number) JsonPath.read(body, "$.id")).longValue();
    }

    public long createProduct(String adminToken, long categoryId, String name, String mrp, String price)
            throws Exception {
        String body = call(post("/admin/products"), adminToken, """
                {"categoryId":%d,"name":"%s","unit":"1 pc","mrp":%s,"price":%s}"""
                .formatted(categoryId, name, mrp, price), status().isCreated());
        return ((Number) JsonPath.read(body, "$.id")).longValue();
    }

    public void setStock(String adminToken, long storeId, long productId, int availableQty) throws Exception {
        call(put("/admin/stores/" + storeId + "/inventory"), adminToken, """
                {"items":[{"productId":%d,"availableQty":%d}]}""".formatted(productId, availableQty),
                status().isOk());
    }

    // Valid format, never assigned to a store by any test.
    public static final String UNSERVED_PINCODE = "999999";

    // A random valid pincode (never UNSERVED_PINCODE), so tests sharing one database don't collide.
    public static String randomPincode() {
        return String.valueOf(ThreadLocalRandom.current().nextInt(100000, 999999));
    }

    public String call(MockHttpServletRequestBuilder request, String token, String json, ResultMatcher expected)
            throws Exception {
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        if (json != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(json);
        }
        return mvc.perform(request).andExpect(expected).andReturn().getResponse().getContentAsString();
    }
}
