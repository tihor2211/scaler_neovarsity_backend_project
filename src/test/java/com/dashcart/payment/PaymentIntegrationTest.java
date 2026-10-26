package com.dashcart.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.ResultMatcher;

import com.dashcart.support.IntegrationTest;
import com.dashcart.support.TestApi;
import com.jayway.jsonpath.JsonPath;

class PaymentIntegrationTest extends IntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    TestApi api;
    String customer;
    long storeId;
    long product;
    long orderId;

    @BeforeEach
    void orderAwaitingPayment() throws Exception {
        api = api();
        String admin = api.adminToken();
        String pincode = TestApi.randomPincode();
        storeId = api.createStore(admin, pincode);
        product = api.createProduct(admin, api.createCategory(admin), "Atta", "300.00", "250.00");
        api.setStock(admin, storeId, product, 10);
        customer = api.newCustomerToken();
        api.call(put("/cart/items/" + product), customer, """
                {"storeId":%d,"quantity":2}""".formatted(storeId), status().isOk());
        String order = api.call(post("/orders/checkout"), customer, """
                {"deliveryAddress":"5 Pay Street","deliveryPincode":"%s"}""".formatted(pincode),
                status().isCreated());
        orderId = ((Number) JsonPath.read(order, "$.id")).longValue();
    }

    @Test
    void startPaymentCreatesRazorpayOrderForTheTotal() throws Exception {
        String start = startPayment();
        assertThat((String) JsonPath.read(start, "$.razorpayOrderId")).startsWith("order_fake_");
        assertThat(((Number) JsonPath.read(start, "$.amountPaise")).longValue()).isEqualTo(50000L);
        assertThat(orderStatus()).isEqualTo("PAYMENT_PENDING");
    }

    @Test
    void verifiedCheckoutPaysOrderAndSellsTheStock() throws Exception {
        String razorpayOrderId = JsonPath.read(startPayment(), "$.razorpayOrderId");
        String signature = Signatures.hmacSha256Hex(
                (razorpayOrderId + "|pay_ok1").getBytes(StandardCharsets.UTF_8), TestApi.RAZORPAY_KEY_SECRET);

        String payment = verify(razorpayOrderId, "pay_ok1", signature, status().isOk());
        assertThat((String) JsonPath.read(payment, "$.status")).isEqualTo("CAPTURED");
        assertThat(paymentStatus(razorpayOrderId)).isEqualTo("CAPTURED");
        assertThat(orderStatus()).isEqualTo("PAID");
        assertThat(stock()).containsExactly(8, 0);
    }

    @Test
    void forgedCheckoutSignatureIsRejected() throws Exception {
        String razorpayOrderId = JsonPath.read(startPayment(), "$.razorpayOrderId");
        verify(razorpayOrderId, "pay_forged", "0".repeat(64), status().isBadRequest());
        assertThat(orderStatus()).isEqualTo("PAYMENT_PENDING");
    }

    @Test
    void webhookPaysOrderAndARepeatDeliveryChangesNothing() throws Exception {
        String razorpayOrderId = JsonPath.read(startPayment(), "$.razorpayOrderId");
        String eventId = "evt_" + UUID.randomUUID();
        String body = capturedEvent(razorpayOrderId, "pay_hook1");

        webhook(body, sign(body), eventId, status().isOk());
        webhook(body, sign(body), eventId, status().isOk());

        assertThat(orderStatus()).isEqualTo("PAID");
        assertThat(paymentStatus(razorpayOrderId)).isEqualTo("CAPTURED");
        assertThat(stock()).as("confirmed once: reserved went 2 -> 0, not below").containsExactly(8, 0);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM webhook_events WHERE event_id = ?", Integer.class,
                eventId)).isEqualTo(1);
    }

    @Test
    void webhookWithWrongSignatureIsRejected() throws Exception {
        String razorpayOrderId = JsonPath.read(startPayment(), "$.razorpayOrderId");
        String body = capturedEvent(razorpayOrderId, "pay_fake");
        webhook(body, "deadbeef", "evt_" + UUID.randomUUID(), status().isBadRequest());
        assertThat(orderStatus()).isEqualTo("PAYMENT_PENDING");
    }

    @Test
    void paymentAfterExpiryIsFlaggedForRefund() throws Exception {
        String razorpayOrderId = JsonPath.read(startPayment(), "$.razorpayOrderId");
        jdbc.update("UPDATE orders SET expires_at = NOW(6) - INTERVAL 1 MINUTE WHERE id = ?", orderId);
        api.call(post("/admin/orders/expire-due"), api.adminToken(), null, status().isOk());

        String body = capturedEvent(razorpayOrderId, "pay_late");
        webhook(body, sign(body), "evt_" + UUID.randomUUID(), status().isOk());

        assertThat(orderStatus()).isEqualTo("EXPIRED");
        assertThat(paymentStatus(razorpayOrderId)).isEqualTo("REFUND_REQUIRED");
        assertThat(stock()).as("expiry released the stock").containsExactly(10, 0);
    }

    @Test
    void cannotPaySomeoneElsesOrder() throws Exception {
        api.call(post("/orders/" + orderId + "/payment"), api.newCustomerToken(), null, status().isNotFound());
    }

    private String startPayment() throws Exception {
        return api.call(post("/orders/" + orderId + "/payment"), customer, null, status().isCreated());
    }

    private String verify(String razorpayOrderId, String paymentId, String signature, ResultMatcher expected)
            throws Exception {
        return api.call(post("/payments/razorpay/verify"), customer, """
                {"razorpayOrderId":"%s","razorpayPaymentId":"%s","razorpaySignature":"%s"}"""
                .formatted(razorpayOrderId, paymentId, signature), expected);
    }

    private void webhook(String body, String signature, String eventId, ResultMatcher expected) throws Exception {
        mvc.perform(post("/payments/razorpay/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.getBytes(StandardCharsets.UTF_8))
                        .header("X-Razorpay-Signature", signature)
                        .header("x-razorpay-event-id", eventId))
                .andExpect(expected);
    }

    private static String sign(String body) {
        return Signatures.hmacSha256Hex(body.getBytes(StandardCharsets.UTF_8), TestApi.RAZORPAY_WEBHOOK_SECRET);
    }

    // Shape of Razorpay's payment.captured webhook (only the fields we read, plus a few real ones).
    private static String capturedEvent(String razorpayOrderId, String paymentId) {
        return """
                {"entity":"event","account_id":"acc_test","event":"payment.captured","contains":["payment"],\
                "payload":{"payment":{"entity":{"id":"%s","entity":"payment","amount":50000,"currency":"INR",\
                "status":"captured","order_id":"%s","method":"upi"}}},"created_at":1767225600}"""
                .formatted(paymentId, razorpayOrderId);
    }

    private String paymentStatus(String razorpayOrderId) {
        return jdbc.queryForObject("SELECT status FROM payments WHERE razorpay_order_id = ?", String.class,
                razorpayOrderId);
    }

    private String orderStatus() {
        return jdbc.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, orderId);
    }

    private int[] stock() {
        return jdbc.queryForObject(
                "SELECT available_qty, reserved_qty FROM store_inventory WHERE store_id = ? AND product_id = ?",
                (rs, n) -> new int[] {rs.getInt(1), rs.getInt(2)}, storeId, product);
    }
}
