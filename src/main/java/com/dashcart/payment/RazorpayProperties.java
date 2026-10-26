package com.dashcart.payment;

import org.springframework.boot.context.properties.ConfigurationProperties;

// keyId / keySecret: Razorpay API keys (test mode keys start with rzp_test_). webhookSecret: the secret
// set when creating the webhook in the Razorpay dashboard. With no keyId, a fake gateway is used.
@ConfigurationProperties(prefix = "dashcart.payment.razorpay")
public record RazorpayProperties(String keyId, String keySecret, String webhookSecret, String baseUrl) {

    public boolean live() {
        return keyId != null && !keyId.isBlank();
    }
}
