package com.dashcart.auth;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.dashcart.auth.AuthDtos.LoginRequest;
import com.dashcart.auth.AuthDtos.LoginResponse;
import com.dashcart.auth.AuthDtos.RegisterRequest;
import com.dashcart.auth.AuthDtos.UserResponse;
import com.dashcart.auth.JwtService.VerifiedToken;

import jakarta.validation.Valid;

@RestController
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/auth/register")
    @ResponseStatus(HttpStatus.CREATED)
    public UserResponse register(@Valid @RequestBody RegisterRequest request) {
        return authService.register(request);
    }

    @PostMapping("/auth/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request);
    }

    @PostMapping("/auth/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(Authentication authentication) {
        authService.logout((VerifiedToken) authentication.getCredentials());
    }

    @GetMapping("/users/me")
    public UserResponse me(@AuthenticationPrincipal AuthUser user) {
        return authService.me(user);
    }
}
