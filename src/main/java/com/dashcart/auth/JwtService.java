package com.dashcart.auth;

import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;

import javax.crypto.SecretKey;

import org.springframework.stereotype.Service;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;

// Issues and verifies HS256-signed access tokens.
// Claims: sub = user id, email, role, jti = unique token id (used to deny-list a token on logout).
@Service
public class JwtService {

    private final SecretKey key;
    private final JwtProperties properties;

    public JwtService(JwtProperties properties) {
        this.properties = properties;
        this.key = Keys.hmacShaKeyFor(Decoders.BASE64.decode(properties.secret()));
    }

    public IssuedToken issue(User user) {
        Instant now = Instant.now();
        Instant expiresAt = now.plus(properties.ttl());
        String token = Jwts.builder()
                .subject(String.valueOf(user.getId()))
                .claim("email", user.getEmail())
                .claim("role", user.getRole().name())
                .id(UUID.randomUUID().toString())
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiresAt))
                // Explicit, so a longer secret doesn't silently switch tokens to HS384/HS512.
                .signWith(key, Jwts.SIG.HS256)
                .compact();
        return new IssuedToken(token, expiresAt);
    }

    // Empty if the signature is wrong, the token is expired or malformed.
    public Optional<VerifiedToken> verify(String token) {
        try {
            Claims claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
            AuthUser user = new AuthUser(
                    Long.valueOf(claims.getSubject()),
                    claims.get("email", String.class),
                    Role.valueOf(claims.get("role", String.class)));
            return Optional.of(new VerifiedToken(user, claims.getId(), claims.getExpiration().toInstant()));
        } catch (JwtException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    public record IssuedToken(String token, Instant expiresAt) {
    }

    public record VerifiedToken(AuthUser user, String tokenId, Instant expiresAt) {
    }
}
