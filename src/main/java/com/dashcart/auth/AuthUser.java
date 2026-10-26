package com.dashcart.auth;

// The logged-in user as seen by controllers (@AuthenticationPrincipal AuthUser user). Built from the JWT, no DB hit.
public record AuthUser(Long id, String email, Role role) {
}
