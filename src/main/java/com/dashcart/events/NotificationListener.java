package com.dashcart.events;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import tools.jackson.databind.ObjectMapper;

// Consumes order events and turns them into customer notifications (the in-app "order updates" inbox;
// in production this is also where SMS / push would be sent). Runs separately from checkout, so a slow
// notification channel never slows down placing an order.
@Component
public class NotificationListener {

    private static final Logger log = LoggerFactory.getLogger(NotificationListener.class);

    private final NotificationRepository notifications;
    private final ObjectMapper mapper;

    public NotificationListener(NotificationRepository notifications, ObjectMapper mapper) {
        this.notifications = notifications;
        this.mapper = mapper;
    }

    @KafkaListener(topics = OrderEventPublisher.TOPIC, groupId = "dashcart-notifications")
    public void onOrderEvent(String json) {
        OrderEvent event = mapper.readValue(json, OrderEvent.class);
        String message = messageFor(event);
        if (message == null || notifications.existsByOrderIdAndStatus(event.orderId(), event.status())) {
            return;
        }
        try {
            notifications.save(new Notification(event.userId(), event.orderId(), event.status(), message));
        } catch (DataIntegrityViolationException duplicate) {
            // Redelivered event processed concurrently: already recorded.
            log.debug("Duplicate order event {} for order {}", event.status(), event.orderId());
        }
    }

    static String messageFor(OrderEvent event) {
        String order = "Order #" + event.orderId();
        return switch (event.status()) {
            case "CREATED" -> order + " placed. Pay within 10 minutes to confirm it.";
            case "PAID" -> order + " confirmed. Payment of Rs " + event.totalAmount() + " received.";
            case "PACKED" -> order + " is packed.";
            case "OUT_FOR_DELIVERY" -> order + " is on its way.";
            case "DELIVERED" -> order + " delivered. Enjoy!";
            case "CANCELLED" -> order + " cancelled.";
            case "EXPIRED" -> order + " expired because payment wasn't completed in time.";
            default -> null;
        };
    }
}
