package com.dashcart.events;

import java.time.Instant;
import java.util.List;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.dashcart.auth.AuthUser;

@RestController
public class NotificationController {

    private final NotificationRepository notifications;

    public NotificationController(NotificationRepository notifications) {
        this.notifications = notifications;
    }

    @GetMapping("/notifications")
    public List<NotificationResponse> mine(@AuthenticationPrincipal AuthUser user) {
        return notifications.findTop50ByUserIdOrderByCreatedAtDescIdDesc(user.id()).stream()
                .map(n -> new NotificationResponse(n.getId(), n.getOrderId(), n.getStatus(), n.getMessage(),
                        n.getCreatedAt()))
                .toList();
    }

    public record NotificationResponse(Long id, Long orderId, String status, String message, Instant createdAt) {
    }
}
