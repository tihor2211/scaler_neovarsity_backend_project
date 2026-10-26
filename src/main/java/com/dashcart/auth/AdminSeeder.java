package com.dashcart.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

// Admins can't self-register. On startup, creates the admin from ADMIN_EMAIL / ADMIN_PASSWORD if both
// are set and that user doesn't exist yet. Nothing is seeded when they're blank.
@Component
public class AdminSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminSeeder.class);

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final String email;
    private final String password;

    public AdminSeeder(UserRepository users, PasswordEncoder passwordEncoder,
            @Value("${dashcart.admin.email:}") String email,
            @Value("${dashcart.admin.password:}") String password) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.email = email;
        this.password = password;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (email.isBlank() || password.isBlank()) {
            return;
        }
        String normalised = AuthService.normalise(email);
        if (!users.existsByEmail(normalised)) {
            users.save(new User("Admin", normalised, null, passwordEncoder.encode(password), Role.ADMIN));
            log.info("Seeded admin user {}", normalised);
        }
    }
}
