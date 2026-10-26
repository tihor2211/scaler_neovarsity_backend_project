package com.dashcart.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import com.dashcart.auth.JwtAuthenticationFilter;

import jakarta.servlet.http.HttpServletResponse;

// Stateless JWT security: no sessions, no CSRF (no cookies), every request carries its own token.
@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, JwtAuthenticationFilter jwtFilter) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health/**").permitAll()
                        .requestMatchers("/actuator/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST, "/auth/register", "/auth/login").permitAll()
                        // Browsing (pincode check, categories, a store's products) works without login.
                        .requestMatchers(HttpMethod.GET, "/stores/**", "/categories").permitAll()
                        // Razorpay calls this; it's authenticated by its HMAC signature instead of a JWT.
                        .requestMatchers(HttpMethod.POST, "/payments/razorpay/webhook").permitAll()
                        // Demo payment page (static HTML); its API calls carry the customer's JWT.
                        .requestMatchers(HttpMethod.GET, "/pay.html").permitAll()
                        .requestMatchers("/admin/**").hasRole("ADMIN")
                        .anyRequest().authenticated())
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, ex) ->
                                writeProblem(response, HttpStatus.UNAUTHORIZED, "Login required or token invalid"))
                        .accessDeniedHandler((request, response, ex) ->
                                writeProblem(response, HttpStatus.FORBIDDEN, "You don't have access to this resource")))
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    // Security errors happen before controllers run, so GlobalExceptionHandler can't format them.
    private static void writeProblem(HttpServletResponse response, HttpStatus status, String detail)
            throws java.io.IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.getWriter().write("""
                {"type":"about:blank","title":"%s","status":%d,"detail":"%s"}"""
                .formatted(status.getReasonPhrase(), status.value(), detail));
    }
}
