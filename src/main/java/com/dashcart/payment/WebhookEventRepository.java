package com.dashcart.payment;

import org.springframework.data.jpa.repository.JpaRepository;

public interface WebhookEventRepository extends JpaRepository<WebhookEvent, Long> {

    boolean existsByProviderAndEventId(String provider, String eventId);
}
