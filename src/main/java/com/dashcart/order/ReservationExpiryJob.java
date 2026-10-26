package com.dashcart.order;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// Every 30 s, releases the stock of orders that weren't paid within the reservation time.
// Safe to run on several app instances at once: each order is expired by a conditional UPDATE, so only
// one instance wins it.
@Component
public class ReservationExpiryJob {

    private static final Logger log = LoggerFactory.getLogger(ReservationExpiryJob.class);

    private final OrderService orderService;

    public ReservationExpiryJob(OrderService orderService) {
        this.orderService = orderService;
    }

    @Scheduled(fixedDelayString = "${dashcart.checkout.expiry-check-interval:PT30S}",
            initialDelayString = "${dashcart.checkout.expiry-check-interval:PT30S}")
    public void run() {
        int expired = orderService.expireDue();
        if (expired > 0) {
            log.info("Expired {} unpaid orders and released their stock", expired);
        }
    }
}
