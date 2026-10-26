package com.dashcart.payment;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

// Razorpay signs with HMAC-SHA256 (hex). Checkout: over "order_id|payment_id" with the key secret.
// Webhooks: over the raw request body with the webhook secret.
public final class Signatures {

    private Signatures() {
    }

    public static String hmacSha256Hex(byte[] message, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(message));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
    }

    // Constant-time comparison, so response timing doesn't leak how much of a forged signature matched.
    public static boolean matches(byte[] message, String secret, String signature) {
        if (secret == null || secret.isBlank() || signature == null) {
            return false;
        }
        byte[] expected = hmacSha256Hex(message, secret).getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, signature.getBytes(StandardCharsets.UTF_8));
    }
}
