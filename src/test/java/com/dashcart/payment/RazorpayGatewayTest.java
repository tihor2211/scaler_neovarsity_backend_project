package com.dashcart.payment;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

// The real HTTP call to Razorpay's Orders API, against a local stand-in server.
class RazorpayGatewayTest {

    private HttpServer razorpay;
    private final AtomicReference<String> method = new AtomicReference<>();
    private final AtomicReference<String> path = new AtomicReference<>();
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private final AtomicReference<String> body = new AtomicReference<>();

    @BeforeEach
    void startFakeRazorpay() throws IOException {
        razorpay = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        razorpay.createContext("/v1/orders", exchange -> {
            method.set(exchange.getRequestMethod());
            path.set(exchange.getRequestURI().getPath());
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = """
                    {"id":"order_Q1w2e3r4t5y6u7","entity":"order","amount":36800,"currency":"INR",
                     "receipt":"dc-8308-ab12cd34","status":"created","attempts":0}"""
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(response);
            }
        });
        razorpay.start();
    }

    @AfterEach
    void stopFakeRazorpay() {
        razorpay.stop(0);
    }

    @Test
    void createsARazorpayOrderWithBasicAuthAndTheAmountInPaise() {
        RazorpayGateway gateway = new RazorpayGateway(new RazorpayProperties("rzp_test_key", "test_secret", "whsec",
                "http://127.0.0.1:" + razorpay.getAddress().getPort()));

        String orderId = gateway.createOrder(36800, "INR", "dc-8308-ab12cd34");

        assertThat(orderId).isEqualTo("order_Q1w2e3r4t5y6u7");
        assertThat(method.get()).isEqualTo("POST");
        assertThat(path.get()).isEqualTo("/v1/orders");
        assertThat(authorization.get()).isEqualTo("Basic "
                + Base64.getEncoder().encodeToString("rzp_test_key:test_secret".getBytes(StandardCharsets.UTF_8)));
        assertThat(body.get()).contains("\"amount\":36800", "\"currency\":\"INR\"", "\"receipt\":\"dc-8308-ab12cd34\"");
        assertThat(gateway.keyId()).isEqualTo("rzp_test_key");
    }

    @Test
    void withoutApiKeysItHandsOutFakeOrderIdsAndCallsNobody() {
        RazorpayGateway gateway = new RazorpayGateway(new RazorpayProperties("", "", "",
                "http://127.0.0.1:" + razorpay.getAddress().getPort()));

        String first = gateway.createOrder(36800, "INR", "dc-1");
        String second = gateway.createOrder(36800, "INR", "dc-2");

        assertThat(first).startsWith("order_fake_").isNotEqualTo(second);
        assertThat(gateway.keyId()).isEqualTo("rzp_fake");
        assertThat(method.get()).as("no HTTP call").isNull();
    }
}
