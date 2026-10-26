package com.dashcart.auth;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dashcart.auth.AuthDtos.LoginRequest;
import com.dashcart.auth.AuthDtos.LoginResponse;
import com.dashcart.auth.AuthDtos.RegisterRequest;
import com.dashcart.auth.AuthDtos.UserResponse;
import com.dashcart.auth.JwtService.IssuedToken;
import com.dashcart.auth.JwtService.VerifiedToken;
import com.dashcart.common.ApiException;

@Service
public class AuthService {

    // Same message for unknown email and wrong password, so the API doesn't reveal which emails exist.
    private static final String BAD_CREDENTIALS = "Invalid email or password";

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final TokenDenylist denylist;

    public AuthService(UserRepository users, PasswordEncoder passwordEncoder, JwtService jwtService,
            TokenDenylist denylist) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.denylist = denylist;
    }

    @Transactional
    public UserResponse register(RegisterRequest request) {
        String email = normalise(request.email());
        if (users.existsByEmail(email)) {
            throw ApiException.conflict("Email is already registered");
        }
        User user = new User(request.name().trim(), email, request.phone(),
                passwordEncoder.encode(request.password()), Role.CUSTOMER);
        try {
            return UserResponse.from(users.saveAndFlush(user));
        } catch (DataIntegrityViolationException e) {
            // Two registrations with the same email at the same moment: the unique key catches the second.
            throw ApiException.conflict("Email is already registered");
        }
    }

    @Transactional(readOnly = true)
    public LoginResponse login(LoginRequest request) {
        User user = users.findByEmail(normalise(request.email()))
                .filter(u -> passwordEncoder.matches(request.password(), u.getPasswordHash()))
                .orElseThrow(() -> ApiException.unauthorized(BAD_CREDENTIALS));
        IssuedToken issued = jwtService.issue(user);
        long expiresIn = Duration.between(Instant.now(), issued.expiresAt()).toSeconds();
        return new LoginResponse(issued.token(), "Bearer", expiresIn);
    }

    public void logout(VerifiedToken token) {
        denylist.deny(token.tokenId(), token.expiresAt());
    }

    @Transactional(readOnly = true)
    public UserResponse me(AuthUser authUser) {
        return users.findById(authUser.id())
                .map(UserResponse::from)
                .orElseThrow(() -> ApiException.notFound("User not found"));
    }

    static String normalise(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
