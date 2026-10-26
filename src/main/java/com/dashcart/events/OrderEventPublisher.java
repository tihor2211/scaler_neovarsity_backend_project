package com.dashcart.events;

import java.time.Instant;

import org.apache.kafka.clients.admin.NewTopic;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.dashcart.order.CustomerOrder;
import com.dashcart.order.OrderStatus;

import tools.jackson.databind.ObjectMapper;

// Sends order status changes to Kafka, only after the database transaction commits, so consumers never
// hear about a change that was rolled back. Sending is asynchronous; if Kafka is down the order still
// goes through and the failure is logged (a transactional outbox would close that gap; see the report).
@Component
public class OrderEventPublisher {

    public static final String TOPIC = "order-events";

    private static final Logger log = LoggerFactory.getLogger(OrderEventPublisher.class);

    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper mapper;

    public OrderEventPublisher(KafkaTemplate<String, String> kafka, ObjectMapper mapper) {
        this.kafka = kafka;
        this.mapper = mapper;
    }

    // Must be called inside the transaction that made the change.
    public void publish(CustomerOrder order, OrderStatus newStatus) {
        OrderEvent event = new OrderEvent(order.getId(), order.getUserId(), order.getStoreId(), newStatus.name(),
                order.getTotalAmount(), Instant.now());
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                send(event);
            }
        });
    }

    private void send(OrderEvent event) {
        try {
            kafka.send(TOPIC, String.valueOf(event.orderId()), mapper.writeValueAsString(event))
                    .whenComplete((result, error) -> {
                        if (error != null) {
                            log.warn("Order event {} for order {} not delivered: {}", event.status(),
                                    event.orderId(), error.getMessage());
                        }
                    });
        } catch (RuntimeException e) {
            log.warn("Order event {} for order {} not sent: {}", event.status(), event.orderId(), e.getMessage());
        }
    }

    @Configuration
    static class Topics {

        @Bean
        NewTopic orderEventsTopic() {
            return TopicBuilder.name(TOPIC).partitions(3).replicas(1).build();
        }
    }
}
