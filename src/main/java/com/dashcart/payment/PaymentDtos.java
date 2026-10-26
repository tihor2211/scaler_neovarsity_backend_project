package com.dashcart.payment;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import jakarta.validation.constraints.NotBlank;

public final class PaymentDtos {

    private PaymentDtos() {
    }

    // What the app needs to open Razorpay Checkout.
    public record StartPaymentResponse(Long orderId, String razorpayOrderId, long amountPaise, String currency,
            String razorpayKeyId, Instant payBefore) {
    }

    // Sent by the app after Razorpay Checkout succeeds (the handler's three fields).
    public record VerifyRequest(@NotBlank String razorpayOrderId, @NotBlank String razorpayPaymentId,
            @NotBlank String razorpaySignature) {
    }

    public record PaymentResponse(Long id, Long orderId, String razorpayOrderId, String razorpayPaymentId,
            long amountPaise, String currency, PaymentStatus status) {

        static PaymentResponse from(Payment p) {
            return new PaymentResponse(p.getId(), p.getOrderId(), p.getRazorpayOrderId(), p.getRazorpayPaymentId(),
                    p.getAmountPaise(), p.getCurrency(), p.getStatus());
        }
    }

    // The parts of a Razorpay webhook we use: event name and payload.payment.entity.
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RazorpayEvent(String event, EventPayload payload) {

        PaymentEntity payment() {
            return payload == null || payload.payment() == null ? null : payload.payment().entity();
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record EventPayload(EntityWrapper payment) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record EntityWrapper(PaymentEntity entity) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PaymentEntity(String id, @JsonProperty("order_id") String orderId, long amount, String status) {
    }
}
