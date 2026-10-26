package com.dashcart.payment;

import java.time.Instant;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

// Every webhook delivery we accepted. The unique (provider, event_id) key is how a repeated delivery of
// the same event is recognised and skipped: Razorpay retries until it gets a 2xx.
@Entity
@Table(name = "webhook_events")
public class WebhookEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 20)
    private String provider;

    @Column(name = "event_id", nullable = false, length = 100)
    private String eventId;

    @Column(name = "event_type", nullable = false, length = 50)
    private String eventType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private String payload;

    @CreationTimestamp
    @Column(name = "received_at", nullable = false, updatable = false)
    private Instant receivedAt;

    @Column(name = "processed_at")
    private Instant processedAt;

    protected WebhookEvent() {
    }

    public WebhookEvent(String provider, String eventId, String eventType, String payload) {
        this.provider = provider;
        this.eventId = eventId;
        this.eventType = eventType;
        this.payload = payload;
    }

    public void processed() {
        this.processedAt = Instant.now();
    }
}
