package com.dashcart.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.dashcart.auth.JwtService.IssuedToken;
import com.dashcart.auth.JwtService.VerifiedToken;

// Plain unit tests: no Spring context, no containers.
class JwtServiceTest {

    // 50 bytes (400 bits): long enough that jjwt would pick HS384 on its own.
    private static final String SECRET = "dGVzdC1vbmx5LXNlY3JldC1mb3ItdGhlLWp3dC1zZXJ2aWNlLXVuaXQtdGVzdHMtMTI=";
    private static final String OTHER_SECRET = "YW5vdGhlci1zZWNyZXQtdGhhdC1zaG91bGQtbmV2ZXItdmVyaWZ5LXRoZS10b2tlbnM=";

    private final JwtService jwt = new JwtService(new JwtProperties(SECRET, Duration.ofHours(1)));

    @Test
    void issuedTokenVerifiesBackToTheSameUser() {
        IssuedToken issued = jwt.issue(user(42L, Role.CUSTOMER));

        VerifiedToken verified = jwt.verify(issued.token()).orElseThrow();

        assertThat(verified.user()).isEqualTo(new AuthUser(42L, "asha@example.com", Role.CUSTOMER));
        assertThat(verified.tokenId()).isNotBlank();
        // JWT expiry has one-second precision.
        assertThat(Duration.between(verified.expiresAt(), issued.expiresAt()).abs()).isLessThan(Duration.ofSeconds(1));
        assertThat(issued.expiresAt()).isBetween(Instant.now().plus(Duration.ofMinutes(59)),
                Instant.now().plus(Duration.ofMinutes(61)));
    }

    @Test
    void tokensAreSignedWithHs256WhateverTheKeyLength() {
        String header = new String(Base64.getUrlDecoder().decode(jwt.issue(user(1L, Role.ADMIN)).token().split("\\.")[0]),
                StandardCharsets.UTF_8);

        assertThat(header).contains("\"alg\":\"HS256\"");
    }

    @Test
    void everyTokenHasItsOwnIdSoLogoutRevokesOnlyThatOne() {
        User user = user(7L, Role.CUSTOMER);

        String first = jwt.verify(jwt.issue(user).token()).orElseThrow().tokenId();
        String second = jwt.verify(jwt.issue(user).token()).orElseThrow().tokenId();

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void tamperedTokenIsRejected() {
        String[] parts = jwt.issue(user(7L, Role.CUSTOMER)).token().split("\\.");
        // Same signature, but the payload now claims the ADMIN role.
        String forgedPayload = Base64.getUrlEncoder().withoutPadding().encodeToString(
                new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8)
                        .replace("CUSTOMER", "ADMIN").getBytes(StandardCharsets.UTF_8));

        assertThat(jwt.verify(parts[0] + "." + forgedPayload + "." + parts[2])).isEmpty();
    }

    @Test
    void tokenSignedWithAnotherSecretIsRejected() {
        JwtService other = new JwtService(new JwtProperties(OTHER_SECRET, Duration.ofHours(1)));

        assertThat(jwt.verify(other.issue(user(7L, Role.CUSTOMER)).token())).isEmpty();
    }

    @Test
    void expiredTokenIsRejected() {
        JwtService expiring = new JwtService(new JwtProperties(SECRET, Duration.ofSeconds(-5)));

        assertThat(jwt.verify(expiring.issue(user(7L, Role.CUSTOMER)).token())).isEmpty();
    }

    @Test
    void malformedTokensAreRejected() {
        assertThat(jwt.verify("not-a-jwt")).isEmpty();
        assertThat(jwt.verify("")).isEmpty();
    }

    private static User user(long id, Role role) {
        User user = new User("Asha Rao", "asha@example.com", null, "bcrypt-hash", role);
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }
}
