package com.dashcart.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.function.Consumer;

import org.assertj.core.api.ThrowingConsumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import com.dashcart.common.ApiException;
import com.dashcart.order.OrderService;

import tools.jackson.databind.json.JsonMapper;

// Razorpay webhook handling with mocked repositories: signature first, then de-duplication by event id,
// then an idempotent capture. The integration tests run the same paths against MySQL.
@ExtendWith(MockitoExtension.class)
class PaymentWebhookTest {

    private static final String WEBHOOK_SECRET = "test-webhook-secret";
    private static final long ORDER_ID = 55L;

    @Mock PaymentRepository payments;
    @Mock WebhookEventRepository webhookEvents;
    @Mock OrderService orderService;
    @Mock RazorpayGateway gateway;
    @Mock TransactionTemplate tx;

    private PaymentService service;
    private Payment payment;

    @BeforeEach
    void setUp() {
        RazorpayProperties properties = new RazorpayProperties("rzp_test_x", "test-key-secret", WEBHOOK_SECRET,
                "https://api.razorpay.com");
        service = new PaymentService(payments, webhookEvents, orderService, gateway, properties, tx,
                JsonMapper.builder().build());
        payment = new Payment(ORDER_ID, "order_1", 36800, "INR");
        lenient().doAnswer(call -> {
            call.<Consumer<TransactionStatus>>getArgument(0).accept(null);
            return null;
        }).when(tx).executeWithoutResult(any());
        lenient().when(webhookEvents.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
        lenient().when(payments.findForUpdate("order_1")).thenReturn(Optional.of(payment));
    }

    @Test
    void wrongSignatureIsRejectedBeforeAnythingIsRead() {
        byte[] body = event("payment.captured");

        assertThatThrownBy(() -> service.handleWebhook(body, "forged", "evt_1")).satisfies(badRequest());

        verifyNoInteractions(payments, webhookEvents, orderService, tx);
    }

    @Test
    void missingEventIdIsRejected() {
        byte[] body = event("payment.captured");

        assertThatThrownBy(() -> service.handleWebhook(body, sign(body), null)).satisfies(badRequest());
    }

    @Test
    void signedButMalformedBodyIsRejected() {
        byte[] body = "not json".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> service.handleWebhook(body, sign(body), "evt_1"))
                .satisfies(badRequest())
                .hasMessage("Webhook body is not valid JSON");
    }

    @Test
    void capturedPaymentPaysTheOrder() {
        when(orderService.markPaid(ORDER_ID)).thenReturn(true);
        byte[] body = event("payment.captured");

        service.handleWebhook(body, sign(body), "evt_1");

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CAPTURED);
        assertThat(payment.getRazorpayPaymentId()).isEqualTo("pay_1");
        verify(payments).save(payment);
    }

    @Test
    void aRepeatedEventIdChangesNothing() {
        when(webhookEvents.existsByProviderAndEventId("razorpay", "evt_1")).thenReturn(true);
        byte[] body = event("payment.captured");

        service.handleWebhook(body, sign(body), "evt_1");

        verify(payments, never()).findForUpdate(any());
        verify(orderService, never()).markPaid(any());
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CREATED);
    }

    @Test
    void anAlreadyCapturedPaymentIsLeftAlone() {
        payment.captured("pay_1", true);
        byte[] body = event("order.paid");

        service.handleWebhook(body, sign(body), "evt_2");

        verify(orderService, never()).markPaid(any());
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CAPTURED);
    }

    @Test
    void paymentForAnExpiredOrderIsFlaggedForRefund() {
        when(orderService.markPaid(ORDER_ID)).thenReturn(false);
        byte[] body = event("payment.captured");

        service.handleWebhook(body, sign(body), "evt_1");

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REFUND_REQUIRED);
    }

    @Test
    void failedPaymentIsRecordedAndTheOrderStaysOpen() {
        byte[] body = event("payment.failed");

        service.handleWebhook(body, sign(body), "evt_3");

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
        verify(orderService, never()).markPaid(any());
    }

    private static byte[] event(String type) {
        return ("""
                {"event":"%s","payload":{"payment":{"entity":
                  {"id":"pay_1","order_id":"order_1","amount":36800,"status":"captured"}}}}"""
                .formatted(type)).getBytes(StandardCharsets.UTF_8);
    }

    private static String sign(byte[] body) {
        return Signatures.hmacSha256Hex(body, WEBHOOK_SECRET);
    }

    private static ThrowingConsumer<Throwable> badRequest() {
        return e -> assertThat(e).isInstanceOfSatisfying(ApiException.class,
                api -> assertThat(api.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
    }
}
