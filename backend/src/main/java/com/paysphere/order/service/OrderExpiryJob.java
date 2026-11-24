package com.paysphere.order.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Slf4j
@Component
@RequiredArgsConstructor
public class OrderExpiryJob {

    private final OrderExpiryService expiryService;

    @Scheduled(fixedDelayString = "${app.order-expiry.interval-ms:60000}",
               initialDelayString = "${app.order-expiry.initial-delay-ms:30000}")
    public void run() {
        try {
            expiryService.expireStaleOrders(Instant.now());
        } catch (RuntimeException e) {
            // A failed run must never kill the scheduler; the next run tries again.
            log.error("Order expiry run failed: {}", e.getMessage(), e);
        }
    }
}