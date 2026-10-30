package com.dashcart.payment;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class SignaturesTest {

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void computesHmacSha256AsLowercaseHex() {
        // The widely published HMAC-SHA256 example value for this key and message.
        assertThat(Signatures.hmacSha256Hex(bytes("The quick brown fox jumps over the lazy dog"), "key"))
                .isEqualTo("f7bc83f430538424b13298e6aa6fb143ef4d59a14946175997479dbc2d1a3cd8");
    }

    @Test
    void acceptsOnlyTheSignatureOfTheExactMessageAndSecret() {
        byte[] body = bytes("{\"event\":\"payment.captured\"}");
        String signature = Signatures.hmacSha256Hex(body, "webhook-secret");

        assertThat(Signatures.matches(body, "webhook-secret", signature)).isTrue();
        assertThat(Signatures.matches(bytes("{\"event\": \"payment.captured\"}"), "webhook-secret", signature))
                .as("one extra space in the body").isFalse();
        assertThat(Signatures.matches(body, "other-secret", signature)).isFalse();
        assertThat(Signatures.matches(body, "webhook-secret", signature.replace(signature.charAt(0), 'x'))).isFalse();
        assertThat(Signatures.matches(body, "webhook-secret", signature.toUpperCase())).isFalse();
    }

    @Test
    void neverMatchesWithoutASecretOrASignature() {
        byte[] body = bytes("anything");

        assertThat(Signatures.matches(body, null, "abc")).isFalse();
        assertThat(Signatures.matches(body, " ", Signatures.hmacSha256Hex(body, " "))).isFalse();
        assertThat(Signatures.matches(body, "secret", null)).isFalse();
    }

    @Test
    void checkoutSignatureCoversOrderIdThenPaymentId() {
        String signature = Signatures.hmacSha256Hex(bytes("order_ABC|pay_XYZ"), "key-secret");

        assertThat(Signatures.matches(bytes("order_ABC|pay_XYZ"), "key-secret", signature)).isTrue();
        assertThat(Signatures.matches(bytes("pay_XYZ|order_ABC"), "key-secret", signature)).isFalse();
        assertThat(Signatures.matches(bytes("order_OTHER|pay_XYZ"), "key-secret", signature)).isFalse();
    }
}
