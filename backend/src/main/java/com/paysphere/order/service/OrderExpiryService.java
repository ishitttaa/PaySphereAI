package com.paysphere.order.service;

import com.paysphere.audit.service.AuditService;
import com.paysphere.order.repository.BusinessOrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderExpiryService {

    /** Extra time after expires_at before an order is closed, so last-minute payers are not cut off. */
    public static final Duration GRACE = Duration.ofMinutes(5);

    private final BusinessOrderRepository orderRepository;
    private final AuditService auditService;

    @Transactional
    public int expireStaleOrders(Instant now) {
        int count = orderRepository.expireStaleOrders(now, now.minus(GRACE));
        if (count > 0) {
            log.info("Expired {} unpaid order(s)", count);
            auditService.record("ORDERS_EXPIRED", "BusinessOrder", null,
                    count + " unpaid order(s) expired", "SYSTEM");
        }
        return count;
    }
}