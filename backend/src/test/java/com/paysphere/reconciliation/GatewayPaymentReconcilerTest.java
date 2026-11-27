package com.paysphere.reconciliation;

import com.paysphere.common.exception.BusinessException;
import com.paysphere.gateway.GatewayException;
import com.paysphere.gateway.PaymentGateway;
import com.paysphere.payment.repository.PaymentRepository;
import com.paysphere.payment.service.PaymentService;
import com.paysphere.reconciliation.ReconciliationQueries.RecentPayment;
import com.paysphere.reconciliation.ReconciliationQueries.UnpaidOrder;
import com.paysphere.risk.entity.RiskSeverity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GatewayPaymentReconcilerTest {

    @Mock private ReconciliationQueries queries;
    @Mock private PaymentGateway paymentGateway;
    @Mock private PaymentService paymentService;
    @Mock private PaymentRepository paymentRepository;
    @InjectMocks private GatewayPaymentReconciler reconciler;

    private final Instant now = Instant.parse("2026-01-01T12:00:00Z");
    private final UUID orderId = UUID.randomUUID();

    private Map<String, Object> gatewayPayment(String id, boolean captured, long ageMinutes, long refunded) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", id);
        m.put("captured", captured);
        m.put("status", captured ? "captured" : "failed");
        m.put("amount", 50000L);
        m.put("currency", "INR");
        m.put("amount_refunded", refunded);
        m.put("created_at", now.minus(ageMinutes, ChronoUnit.MINUTES).getEpochSecond());
        return m;
    }

    private void oneUnpaidOrder() {
        when(queries.findRecentOrdersWithoutPayment(any(), anyInt()))
                .thenReturn(List.of(new UnpaidOrder(orderId, "order_1", "ORD-1")));
        when(paymentRepository.existsByRazorpayPaymentId("pay_1")).thenReturn(false);
    }

    @Test
    void recordsAPaymentTheGatewayCapturedButWeNeverRecorded() {
        oneUnpaidOrder();
        when(paymentGateway.fetchPaymentsForOrder("order_1"))
                .thenReturn(List.of(gatewayPayment("pay_1", true, 30, 0)));

        GatewayPaymentResult result = reconciler.reconcile(now);

        verify(paymentService).recordGatewayCapture(eq("pay_1"), eq("order_1"), any(), eq("RECONCILIATION"));
        assertThat(result.outcomes()).containsEntry("RECORDED", 1);
        assertThat(result.findings()).isEmpty();
    }

    @Test
    void leavesAVeryRecentPaymentAlone() {
        when(queries.findRecentOrdersWithoutPayment(any(), anyInt()))
                .thenReturn(List.of(new UnpaidOrder(orderId, "order_1", "ORD-1")));
        when(paymentGateway.fetchPaymentsForOrder("order_1"))
                .thenReturn(List.of(gatewayPayment("pay_1", true, 3, 0)));

        reconciler.reconcile(now);

        verify(paymentService, never()).recordGatewayCapture(any(), any(), any(), any());
    }

    @Test
    void skipsAPaymentWeAlreadyHave() {
        when(queries.findRecentOrdersWithoutPayment(any(), anyInt()))
                .thenReturn(List.of(new UnpaidOrder(orderId, "order_1", "ORD-1")));
        when(paymentGateway.fetchPaymentsForOrder("order_1"))
                .thenReturn(List.of(gatewayPayment("pay_1", true, 30, 0)));
        when(paymentRepository.existsByRazorpayPaymentId("pay_1")).thenReturn(true);

        reconciler.reconcile(now);

        verify(paymentService, never()).recordGatewayCapture(any(), any(), any(), any());
    }

    @Test
    void ignoresAGatewayPaymentThatNeverCaptured() {
        when(queries.findRecentOrdersWithoutPayment(any(), anyInt()))
                .thenReturn(List.of(new UnpaidOrder(orderId, "order_1", "ORD-1")));
        when(paymentGateway.fetchPaymentsForOrder("order_1"))
                .thenReturn(List.of(gatewayPayment("pay_1", false, 30, 0)));

        GatewayPaymentResult result = reconciler.reconcile(now);

        verify(paymentService, never()).recordGatewayCapture(any(), any(), any(), any());
        assertThat(result.findings()).isEmpty();
    }

    @Test
    void flagsAPaymentThatCouldNotBeRecorded() {
        oneUnpaidOrder();
        when(paymentGateway.fetchPaymentsForOrder("order_1"))
                .thenReturn(List.of(gatewayPayment("pay_1", true, 30, 0)));
        doThrow(new BusinessException("AMOUNT_MISMATCH", "amount differs"))
                .when(paymentService).recordGatewayCapture(any(), any(), any(), any());

        GatewayPaymentResult result = reconciler.reconcile(now);

        assertThat(result.outcomes()).containsEntry("RECORD_FAILED", 1);
        assertThat(result.findings()).singleElement().satisfies(f -> {
            assertThat(f.ruleCode()).isEqualTo("RECON_GATEWAY_PAYMENT_NOT_RECORDED");
            assertThat(f.severity()).isEqualTo(RiskSeverity.CRITICAL);
            assertThat(f.entityId()).isEqualTo(orderId);
        });
    }

    @Test
    void doesNotAutoRecordAPaymentThatIsAlreadyRefundedAtTheGateway() {
        oneUnpaidOrder();
        when(paymentGateway.fetchPaymentsForOrder("order_1"))
                .thenReturn(List.of(gatewayPayment("pay_1", true, 30, 10000)));

        GatewayPaymentResult result = reconciler.reconcile(now);

        verify(paymentService, never()).recordGatewayCapture(any(), any(), any(), any());
        assertThat(result.outcomes()).containsEntry("NEEDS_REVIEW", 1);
        assertThat(result.findings()).hasSize(1);
    }

    @Test
    void flagsARecordedPaymentWhoseAmountDiffersFromTheGateway() {
        UUID paymentId = UUID.randomUUID();
        when(queries.findRecentCapturedPayments(any(), anyInt()))
                .thenReturn(List.of(new RecentPayment(paymentId, "pay_2", 50100L, "INR")));
        when(paymentGateway.fetchPayment("pay_2")).thenReturn(gatewayPayment("pay_2", true, 30, 0));

        GatewayPaymentResult result = reconciler.reconcile(now);

        assertThat(result.findings()).singleElement().satisfies(f -> {
            assertThat(f.ruleCode()).isEqualTo("RECON_GATEWAY_PAYMENT_MISMATCH");
            assertThat(f.entityId()).isEqualTo(paymentId);
        });
    }

    @Test
    void flagsARecordedPaymentThatTheGatewayDoesNotHoldAsCaptured() {
        UUID paymentId = UUID.randomUUID();
        when(queries.findRecentCapturedPayments(any(), anyInt()))
                .thenReturn(List.of(new RecentPayment(paymentId, "pay_2", 50000L, "INR")));
        when(paymentGateway.fetchPayment("pay_2")).thenReturn(gatewayPayment("pay_2", false, 30, 0));

        assertThat(reconciler.reconcile(now).findings()).hasSize(1);
    }

    @Test
    void aMatchingPaymentProducesNoFinding() {
        when(queries.findRecentCapturedPayments(any(), anyInt()))
                .thenReturn(List.of(new RecentPayment(UUID.randomUUID(), "pay_2", 50000L, "INR")));
        when(paymentGateway.fetchPayment("pay_2")).thenReturn(gatewayPayment("pay_2", true, 30, 0));

        GatewayPaymentResult result = reconciler.reconcile(now);

        assertThat(result.findings()).isEmpty();
        assertThat(result.outcomes()).containsEntry("PAYMENTS_MATCHED", 1);
    }

    @Test
    void oneGatewayErrorDoesNotStopTheOtherOrders() {
        when(queries.findRecentOrdersWithoutPayment(any(), anyInt())).thenReturn(List.of(
                new UnpaidOrder(UUID.randomUUID(), "order_a", "ORD-A"),
                new UnpaidOrder(orderId, "order_1", "ORD-1")));
        when(paymentGateway.fetchPaymentsForOrder("order_a"))
                .thenThrow(GatewayException.fromHttpStatus(503, "unavailable"));
        when(paymentGateway.fetchPaymentsForOrder("order_1"))
                .thenReturn(List.of(gatewayPayment("pay_1", true, 30, 0)));
        when(paymentRepository.existsByRazorpayPaymentId("pay_1")).thenReturn(false);

        GatewayPaymentResult result = reconciler.reconcile(now);

        assertThat(result.outcomes()).containsEntry("ORDER_LOOKUP_ERROR", 1).containsEntry("RECORDED", 1);
    }
}