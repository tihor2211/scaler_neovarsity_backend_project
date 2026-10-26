package com.dashcart.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.dashcart.support.IntegrationTest;
import com.dashcart.support.TestApi;
import com.jayway.jsonpath.JsonPath;

// Order status changes travel through Kafka to the notification consumer and show up in the inbox.
class NotificationIntegrationTest extends IntegrationTest {

    @Test
    void placedAndCancelledOrderProducesTwoNotifications() throws Exception {
        TestApi api = api();
        String admin = api.adminToken();
        String pincode = TestApi.randomPincode();
        long storeId = api.createStore(admin, pincode);
        long product = api.createProduct(admin, api.createCategory(admin), "Curd", "45.00", "42.00");
        api.setStock(admin, storeId, product, 5);
        String customer = api.newCustomerToken();
        api.call(put("/cart/items/" + product), customer, """
                {"storeId":%d,"quantity":1}""".formatted(storeId), status().isOk());
        String order = api.call(post("/orders/checkout"), customer, """
                {"deliveryAddress":"7 Event Road","deliveryPincode":"%s"}""".formatted(pincode), status().isCreated());
        long orderId = ((Number) JsonPath.read(order, "$.id")).longValue();
        api.call(post("/orders/" + orderId + "/cancel"), customer, null, status().isOk());

        List<String> statuses = List.of();
        for (int i = 0; i < 60 && statuses.size() < 2; i++) {
            Thread.sleep(250);
            String inbox = mvc.perform(get("/notifications").header("Authorization", "Bearer " + customer))
                    .andReturn().getResponse().getContentAsString();
            statuses = JsonPath.read(inbox, "$[*].status");
        }
        assertThat(statuses).containsExactlyInAnyOrder("CREATED", "CANCELLED");
    }

    @Test
    void everyStatusHasAMessage() {
        for (String status : List.of("CREATED", "PAID", "PACKED", "OUT_FOR_DELIVERY", "DELIVERED", "CANCELLED",
                "EXPIRED")) {
            assertThat(NotificationListener.messageFor(new OrderEvent(1L, 1L, 1L, status, null, null))).isNotBlank();
        }
    }
}
