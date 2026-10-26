package com.dashcart.payment;

import java.util.Map;
import java.util.UUID;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

// Creates Razorpay orders (POST /v1/orders, Basic auth with key id and secret, amount in paise).
// Without API keys (local runs and tests) it returns fake order ids, so the rest of the flow still works.
@Component
public class RazorpayGateway {

    private final RazorpayProperties properties;
    private final RestClient client;

    public RazorpayGateway(RazorpayProperties properties) {
        this.properties = properties;
        this.client = properties.live()
                ? RestClient.builder().baseUrl(properties.baseUrl())
                        .defaultHeaders(h -> h.setBasicAuth(properties.keyId(), properties.keySecret()))
                        .build()
                : null;
    }

    public String createOrder(long amountPaise, String currency, String receipt) {
        if (client == null) {
            return "order_fake_" + UUID.randomUUID().toString().replace("-", "").substring(0, 14);
        }
        RazorpayOrder order = client.post()
                .uri("/v1/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("amount", amountPaise, "currency", currency, "receipt", receipt))
                .retrieve()
                .body(RazorpayOrder.class);
        return order.id();
    }

    public String keyId() {
        return properties.live() ? properties.keyId() : "rzp_fake";
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record RazorpayOrder(String id, String status, long amount) {
    }
}
