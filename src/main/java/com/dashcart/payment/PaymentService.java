package com.dashcart.payment;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.dashcart.auth.AuthUser;
import com.dashcart.common.ApiException;
import com.dashcart.order.CustomerOrder;
import com.dashcart.order.OrderService;
import com.dashcart.order.OrderStatus;
import com.dashcart.payment.PaymentDtos.PaymentEntity;
import com.dashcart.payment.PaymentDtos.PaymentResponse;
import com.dashcart.payment.PaymentDtos.RazorpayEvent;
import com.dashcart.payment.PaymentDtos.StartPaymentResponse;
import com.dashcart.payment.PaymentDtos.VerifyRequest;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

// Razorpay payments. A payment is confirmed by whichever arrives first, the app's verify call or
// Razorpay's webhook; both check Razorpay's signature, and the second one finds the work already done.
@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);
    private static final String PROVIDER = "razorpay";
    private static final String CURRENCY = "INR";

    private final PaymentRepository payments;
    private final WebhookEventRepository webhookEvents;
    private final OrderService orderService;
    private final RazorpayGateway gateway;
    private final RazorpayProperties properties;
    private final TransactionTemplate tx;
    private final ObjectMapper mapper;

    public PaymentService(PaymentRepository payments, WebhookEventRepository webhookEvents, OrderService orderService,
            RazorpayGateway gateway, RazorpayProperties properties, TransactionTemplate tx, ObjectMapper mapper) {
        this.payments = payments;
        this.webhookEvents = webhookEvents;
        this.orderService = orderService;
        this.gateway = gateway;
        this.properties = properties;
        this.tx = tx;
        this.mapper = mapper;
    }

    // Creates a Razorpay order for this attempt. The call to Razorpay happens outside any database
    // transaction, so a slow payment gateway never holds database locks.
    public StartPaymentResponse start(Long orderId, AuthUser user) {
        CustomerOrder order = orderService.getOwned(orderId, user);
        if (!OrderStatus.HOLDING_STOCK.contains(order.getStatus())) {
            throw ApiException.conflict("Order " + orderId + " is " + order.getStatus() + " and can't be paid");
        }
        if (order.getExpiresAt().isBefore(Instant.now())) {
            throw ApiException.conflict("Order " + orderId + " has expired. Please check out again.");
        }
        long amountPaise = order.getTotalAmount().movePointRight(2).longValueExact();
        // Receipt: our reference on Razorpay's side; unique and at most 40 characters.
        String receipt = "dc-" + orderId + "-" + UUID.randomUUID().toString().substring(0, 8);
        String razorpayOrderId = gateway.createOrder(amountPaise, CURRENCY, receipt);
        tx.executeWithoutResult(status -> {
            payments.save(new Payment(orderId, razorpayOrderId, amountPaise, CURRENCY));
            orderService.markPaymentPending(orderId);
        });
        return new StartPaymentResponse(orderId, razorpayOrderId, amountPaise, CURRENCY, gateway.keyId(),
                order.getExpiresAt());
    }

    // Razorpay Checkout's success handler result, forwarded by the app. Razorpay's docs say to build the
    // signed string from our own stored order id, not the one the client sends back.
    public PaymentResponse verify(VerifyRequest request, AuthUser user) {
        return tx.execute(status -> {
            Payment payment = payments.findForUpdate(request.razorpayOrderId())
                    .orElseThrow(() -> ApiException.notFound("Payment not found"));
            orderService.getOwned(payment.getOrderId(), user);
            byte[] signed = (payment.getRazorpayOrderId() + "|" + request.razorpayPaymentId())
                    .getBytes(StandardCharsets.UTF_8);
            if (!Signatures.matches(signed, properties.keySecret(), request.razorpaySignature())) {
                throw ApiException.badRequest("Payment signature is invalid");
            }
            capture(payment, request.razorpayPaymentId());
            return PaymentResponse.from(payment);
        });
    }

    // Razorpay webhook: check the signature over the raw body, skip events already processed, then apply.
    public void handleWebhook(byte[] body, String signature, String eventId) {
        if (!Signatures.matches(body, properties.webhookSecret(), signature)) {
            throw ApiException.badRequest("Invalid webhook signature");
        }
        if (eventId == null || eventId.isBlank()) {
            throw ApiException.badRequest("Missing x-razorpay-event-id header");
        }
        RazorpayEvent event;
        try {
            event = mapper.readValue(body, RazorpayEvent.class);
        } catch (JacksonException e) {
            throw ApiException.badRequest("Webhook body is not valid JSON");
        }
        try {
            tx.executeWithoutResult(status -> apply(event, eventId, new String(body, StandardCharsets.UTF_8)));
        } catch (DataIntegrityViolationException duplicate) {
            // The same event delivered twice at the same moment: the other delivery recorded it.
            log.info("Duplicate Razorpay webhook {} ignored", eventId);
        }
    }

    public List<PaymentResponse> forOrder(Long orderId, AuthUser user) {
        orderService.getOwned(orderId, user);
        return payments.findByOrderIdOrderByIdDesc(orderId).stream().map(PaymentResponse::from).toList();
    }

    private void apply(RazorpayEvent event, String eventId, String payload) {
        if (webhookEvents.existsByProviderAndEventId(PROVIDER, eventId)) {
            return;
        }
        WebhookEvent record = webhookEvents.saveAndFlush(new WebhookEvent(PROVIDER, eventId, event.event(), payload));
        PaymentEntity entity = event.payment();
        if (entity != null && entity.orderId() != null) {
            payments.findForUpdate(entity.orderId()).ifPresent(payment -> {
                switch (event.event()) {
                    case "payment.captured", "order.paid" -> capture(payment, entity.id());
                    case "payment.failed" -> {
                        if (payment.getStatus() == PaymentStatus.CREATED) {
                            payment.failed(entity.id());
                        }
                    }
                    default -> log.debug("Ignoring Razorpay event {}", event.event());
                }
            });
        }
        record.processed();
    }

    // Idempotent: a payment already captured (or flagged for refund) is left alone.
    private void capture(Payment payment, String razorpayPaymentId) {
        if (payment.getStatus() == PaymentStatus.CAPTURED || payment.getStatus() == PaymentStatus.REFUND_REQUIRED) {
            return;
        }
        boolean accepted = orderService.markPaid(payment.getOrderId());
        payment.captured(razorpayPaymentId, accepted);
        // markPaid's bulk UPDATE clears the persistence context, which detaches this payment, so its change
        // must be saved explicitly (the row lock from findForUpdate is still held by this transaction).
        payments.save(payment);
        if (!accepted) {
            log.warn("Payment {} for order {} arrived after the order stopped accepting payment; refund needed",
                    razorpayPaymentId, payment.getOrderId());
        }
    }
}
