package com.dashcart.auth;

import java.io.IOException;
import java.util.List;

import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

// Reads "Authorization: Bearer <jwt>" and, if the token is valid and not logged out, marks the request
// as authenticated. A missing or bad token just leaves the request anonymous; Spring Security then
// returns 401 for endpoints that need a login.
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER = "Bearer ";

    private final JwtService jwtService;
    private final TokenDenylist denylist;

    public JwtAuthenticationFilter(JwtService jwtService, TokenDenylist denylist) {
        this.jwtService = jwtService;
        this.denylist = denylist;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.startsWith(BEARER)) {
            String token = header.substring(BEARER.length());
            jwtService.verify(token)
                    .filter(verified -> !denylist.isDenied(verified.tokenId()))
                    .ifPresent(verified -> {
                        var authority = new SimpleGrantedAuthority("ROLE_" + verified.user().role().name());
                        var authentication = new UsernamePasswordAuthenticationToken(
                                verified.user(), verified, List.of(authority));
                        SecurityContextHolder.getContext().setAuthentication(authentication);
                    });
        }
        chain.doFilter(request, response);
    }
}
