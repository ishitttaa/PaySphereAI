package com.paysphere.order;

import com.paysphere.audit.service.AuditService;
import com.paysphere.order.repository.BusinessOrderRepository;
import com.paysphere.order.service.OrderExpiryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderExpiryServiceTest {

    @Mock private BusinessOrderRepository orderRepository;
    @Mock private AuditService auditService;
    @InjectMocks private OrderExpiryService service;

    private final Instant now = Instant.parse("2026-01-01T12:00:00Z");

    @Test
    void usesCutoffWithGraceAndAuditsWhenOrdersExpire() {
        when(orderRepository.expireStaleOrders(now, now.minus(Duration.ofMinutes(5)))).thenReturn(3);

        assertThat(service.expireStaleOrders(now)).isEqualTo(3);

        verify(auditService).record(eq("ORDERS_EXPIRED"), eq("BusinessOrder"), isNull(),
                contains("3"), eq("SYSTEM"));
    }

    @Test
    void doesNotWriteAnAuditEntryWhenNothingExpires() {
        when(orderRepository.expireStaleOrders(now, now.minus(Duration.ofMinutes(5)))).thenReturn(0);

        assertThat(service.expireStaleOrders(now)).isZero();

        verify(auditService, never()).record(any(), any(), any(), any(), any());
    }
}
