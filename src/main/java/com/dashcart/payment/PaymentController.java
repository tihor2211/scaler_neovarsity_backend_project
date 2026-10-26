package com.dashcart.payment;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.dashcart.auth.AuthUser;
import com.dashcart.payment.PaymentDtos.PaymentResponse;
import com.dashcart.payment.PaymentDtos.StartPaymentResponse;
import com.dashcart.payment.PaymentDtos.VerifyRequest;

import jakarta.validation.Valid;

@RestController
public class PaymentController {

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @PostMapping("/orders/{orderId}/payment")
    @ResponseStatus(HttpStatus.CREATED)
    public StartPaymentResponse start(@AuthenticationPrincipal AuthUser user, @PathVariable Long orderId) {
        return paymentService.start(orderId, user);
    }

    @GetMapping("/orders/{orderId}/payments")
    public List<PaymentResponse> list(@AuthenticationPrincipal AuthUser user, @PathVariable Long orderId) {
        return paymentService.forOrder(orderId, user);
    }

    @PostMapping("/payments/razorpay/verify")
    public PaymentResponse verify(@AuthenticationPrincipal AuthUser user, @Valid @RequestBody VerifyRequest request) {
        return paymentService.verify(request, user);
    }

    // Called by Razorpay, not by users: no JWT. Trust comes from the X-Razorpay-Signature check.
    // The body is taken as raw bytes because the signature covers the exact bytes Razorpay sent.
    @PostMapping("/payments/razorpay/webhook")
    public Map<String, String> webhook(@RequestBody byte[] body,
            @RequestHeader(name = "X-Razorpay-Signature", required = false) String signature,
            @RequestHeader(name = "x-razorpay-event-id", required = false) String eventId) {
        paymentService.handleWebhook(body, signature, eventId);
        return Map.of("status", "ok");
    }
}
