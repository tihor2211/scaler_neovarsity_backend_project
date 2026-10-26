package com.dashcart.auth;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

// dashcart.jwt.secret: Base64-encoded HMAC key of at least 256 bits. dashcart.jwt.ttl: token lifetime.
@ConfigurationProperties(prefix = "dashcart.jwt")
public record JwtProperties(String secret, Duration ttl) {
}
