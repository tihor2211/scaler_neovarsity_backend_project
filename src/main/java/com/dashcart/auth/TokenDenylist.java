package com.dashcart.auth;

import java.time.Duration;
import java.time.Instant;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

// JWTs are stateless, so logout works by remembering the token id in Redis until the token would
// have expired anyway. The key deletes itself (TTL), so the deny-list never grows without bound.
@Component
public class TokenDenylist {

    private static final String PREFIX = "auth:denylist:";

    private final StringRedisTemplate redis;

    public TokenDenylist(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public void deny(String tokenId, Instant expiresAt) {
        Duration remaining = Duration.between(Instant.now(), expiresAt);
        if (!remaining.isNegative() && !remaining.isZero()) {
            redis.opsForValue().set(PREFIX + tokenId, "1", remaining);
        }
    }

    public boolean isDenied(String tokenId) {
        return Boolean.TRUE.equals(redis.hasKey(PREFIX + tokenId));
    }
}
