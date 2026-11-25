package com.paysphere.refund;

import com.paysphere.audit.service.AuditService;
import com.paysphere.common.exception.BusinessException;
import com.paysphere.gateway.PaymentGateway;
import com.paysphere.ledger.service.LedgerService;
import com.paysphere.order.entity.BusinessOrder;
import com.paysphere.order.entity.BusinessOrderStatus;
import com.paysphere.order.repository.BusinessOrderRepository;
import com.paysphere.payment.entity.Payment;
import com.paysphere.payment.entity.PaymentStatus;
import com.paysphere.payment.repository.PaymentRepository;
import com.paysphere.refund.dto.CreateRefundRequest;
import com.paysphere.refund.dto.RefundResponse;
import com.paysphere.refund.entity.Refund;
import com.paysphere.refund.entity.RefundStatus;
import com.paysphere.refund.repository.RefundRepository;
import com.paysphere.refund.service.RefundService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.util.List;
import org.mockito.ArgumentCaptor;
import com.paysphere.common.exception.InvalidRequestException;
import com.paysphere.gateway.GatewayException;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RefundServiceTest {

    @Mock private AuditService auditService;
    @Mock private RefundRepository refundRepository;
    @Mock private PaymentRepository paymentRepository;
    @Mock private BusinessOrderRepository orderRepository;
    @Mock private PaymentGateway paymentGateway;
    @Mock private LedgerService ledgerService;
    @InjectMocks private RefundService refundService;

    private final UUID paymentId = UUID.randomUUID();

    private Payment payment() {
        BusinessOrder order = BusinessOrder.builder()
                .orderReference("ORD-1").amountPaise(100000L).currency("INR")
                .status(BusinessOrderStatus.PAYMENT_CAPTURED).build();
        return Payment.builder()
                .id(paymentId).businessOrder(order).razorpayPaymentId("pay_abc")
                .status(PaymentStatus.CAPTURED).amountPaise(100000L).currency("INR").build();
    }

    private CreateRefundRequest request(Long amount) {
        return new CreateRefundRequest(paymentId, amount, "customer request", null);
    }

    @Test
    @DisplayName("same Idempotency-Key returns the earlier refund and never calls Razorpay")
    void testDuplicateKeyReturnsExisting() {
        Payment p = payment();
        when(paymentRepository.findByIdForUpdate(paymentId)).thenReturn(Optional.of(p));
        Refund existing = Refund.builder().id(UUID.randomUUID()).payment(p)
                .businessOrder(p.getBusinessOrder()).amountPaise(40000L)
                .status(RefundStatus.PROCESSED).idempotencyKey("client-retry-key-001").build();
        when(refundRepository.findByIdempotencyKey("client-retry-key-001")).thenReturn(Optional.of(existing));

        RefundResponse r = refundService.createRefund(request(40000L), "retry-key-001");

        assertThat(r.id()).isEqualTo(existing.getId());
        verify(paymentGateway, never()).createRefund(any(), anyLong(), any(), any());
        verify(ledgerService, never()).postRefund(any(), anyLong(), any());
    }

    @Test
    @DisplayName("timeout leaves the refund PENDING, not FAILED, and posts no ledger entry")
    void testTimeoutLeavesPending() {
        Payment p = payment();
        when(paymentRepository.findByIdForUpdate(paymentId)).thenReturn(Optional.of(p));
        when(refundRepository.findByIdempotencyKey(anyString())).thenReturn(Optional.empty());
        when(refundRepository.save(any(Refund.class))).thenAnswer(i -> i.getArgument(0));
        when(paymentGateway.createRefund(any(), anyLong(), any(), any()))
                .thenThrow(new RuntimeException("Failed to create refund: timed out",
                        new java.net.http.HttpTimeoutException("request timed out")));

        RefundResponse r = refundService.createRefund(request(30000L), "timeout-key-001");

        assertThat(r.status()).isEqualTo(RefundStatus.PENDING);
        assertThat(p.getAmountRefundedPaise()).isZero();
        verify(ledgerService, never()).postRefund(any(), anyLong(), any());
    }

    @Test
    @DisplayName("a clear 4xx rejection from Razorpay marks the refund FAILED")
    void testRazorpayRejectionMarksFailed() {
        Payment p = payment();
        when(paymentRepository.findByIdForUpdate(paymentId)).thenReturn(Optional.of(p));
        when(refundRepository.findByIdempotencyKey(anyString())).thenReturn(Optional.empty());
        when(refundRepository.save(any(Refund.class))).thenAnswer(i -> i.getArgument(0));
        when(paymentGateway.createRefund(any(), anyLong(), any(), any()))
                .thenThrow(GatewayException.fromHttpStatus(400, "bad request"));

        RefundResponse r = refundService.createRefund(request(30000L), "reject-key-001");

        assertThat(r.status()).isEqualTo(RefundStatus.FAILED);
        verify(ledgerService, never()).postRefund(any(), anyLong(), any());
    }

    @Test
    @DisplayName("successful refund updates the payment and posts to the ledger")
    void testSuccessPostsLedger() {
        Payment p = payment();
        when(paymentRepository.findByIdForUpdate(paymentId)).thenReturn(Optional.of(p));
        when(refundRepository.findByIdempotencyKey(anyString())).thenReturn(Optional.empty());
        when(refundRepository.save(any(Refund.class))).thenAnswer(i -> i.getArgument(0));
        when(paymentGateway.createRefund(any(), anyLong(), any(), any()))
                .thenReturn(Map.of("id", "rfnd_1", "status", "processed",
                        "amount", 30000L, "payment_id", "pay_abc"));

        RefundResponse r = refundService.createRefund(request(30000L), "success-key-001");

        assertThat(r.status()).isEqualTo(RefundStatus.PROCESSED);
        assertThat(p.getAmountRefundedPaise()).isEqualTo(30000L);
        verify(ledgerService).postRefund(any(), eq(30000L), eq("INR"));
    }

    @Test
    @DisplayName("refund larger than the remaining balance is rejected before Razorpay is called")
    void testOverRefundRejected() {
        Payment p = payment();
        when(paymentRepository.findByIdForUpdate(paymentId)).thenReturn(Optional.of(p));
        when(refundRepository.findByIdempotencyKey(anyString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> refundService.createRefund(request(150000L), "over-key-0001"))
                .isInstanceOf(BusinessException.class);

        verify(refundRepository, never()).save(any());
        verify(paymentGateway, never()).createRefund(any(), anyLong(), any(), any());
    }
        @Test
    @DisplayName("applyProcessedRefund completes a PENDING refund: totals, order status, ledger, audit")
    void testApplyProcessedRefundPartial() {
        Payment p = payment();
        Refund pending = Refund.builder().id(UUID.randomUUID()).payment(p)
                .businessOrder(p.getBusinessOrder()).amountPaise(30000L).currency("INR")
                .status(RefundStatus.PENDING).build();
        when(refundRepository.save(any(Refund.class))).thenAnswer(i -> i.getArgument(0));

        Refund result = refundService.applyProcessedRefund(pending, p, "rfnd_late_1", "WEBHOOK");

        assertThat(result.getStatus()).isEqualTo(RefundStatus.PROCESSED);
        assertThat(result.getRazorpayRefundId()).isEqualTo("rfnd_late_1");
        assertThat(p.getAmountRefundedPaise()).isEqualTo(30000L);
        assertThat(p.getBusinessOrder().getStatus()).isEqualTo(BusinessOrderStatus.PARTIALLY_REFUNDED);
        verify(ledgerService).postRefund(eq(pending.getId()), eq(30000L), eq("INR"));
        verify(auditService).record(eq("REFUND_PROCESSED"), eq("Refund"), eq(pending.getId()),
                anyString(), eq("WEBHOOK"));
    }

    @Test
    @DisplayName("applyProcessedRefund for the full amount marks payment and order REFUNDED")
    void testApplyProcessedRefundFull() {
        Payment p = payment();
        Refund pending = Refund.builder().id(UUID.randomUUID()).payment(p)
                .businessOrder(p.getBusinessOrder()).amountPaise(100000L).currency("INR")
                .status(RefundStatus.PENDING).build();
        when(refundRepository.save(any(Refund.class))).thenAnswer(i -> i.getArgument(0));

        refundService.applyProcessedRefund(pending, p, "rfnd_full_1", "WEBHOOK");

        assertThat(p.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(p.getBusinessOrder().getStatus()).isEqualTo(BusinessOrderStatus.REFUNDED);
        verify(ledgerService).postRefund(eq(pending.getId()), eq(100000L), eq("INR"));
    }
        private void stubPayment(Payment p) {
        when(paymentRepository.findIdByRazorpayPaymentId("pay_abc")).thenReturn(Optional.of(paymentId));
        when(paymentRepository.findByIdForUpdate(paymentId)).thenReturn(Optional.of(p));
    }

    @Test
    @DisplayName("webhook completes a PENDING refund that timed out on our side")
    void testWebhookCompletesPendingRefund() {
        Payment p = payment();
        Refund pending = Refund.builder().id(UUID.randomUUID()).payment(p)
                .businessOrder(p.getBusinessOrder()).amountPaise(30000L).currency("INR")
                .status(RefundStatus.PENDING).idempotencyKey("client-timeout-key").build();
        stubPayment(p);
        when(refundRepository.findByRazorpayRefundId("rfnd_9")).thenReturn(Optional.empty());
        when(refundRepository.findByPaymentId(paymentId)).thenReturn(List.of(pending));
        when(refundRepository.save(any(Refund.class))).thenAnswer(i -> i.getArgument(0));

        refundService.handleWebhookRefundProcessed("rfnd_9", "pay_abc", 30000L);

        assertThat(pending.getStatus()).isEqualTo(RefundStatus.PROCESSED);
        assertThat(pending.getRazorpayRefundId()).isEqualTo("rfnd_9");
        assertThat(p.getAmountRefundedPaise()).isEqualTo(30000L);
        verify(ledgerService).postRefund(eq(pending.getId()), eq(30000L), eq("INR"));
    }

    @Test
    @DisplayName("a webhook for an already processed refund changes nothing")
    void testWebhookIsIdempotent() {
        Payment p = payment();
        Refund done = Refund.builder().id(UUID.randomUUID()).payment(p).amountPaise(30000L)
                .status(RefundStatus.PROCESSED).razorpayRefundId("rfnd_9").build();
        stubPayment(p);
        when(refundRepository.findByRazorpayRefundId("rfnd_9")).thenReturn(Optional.of(done));

        refundService.handleWebhookRefundProcessed("rfnd_9", "pay_abc", 30000L);

        verify(ledgerService, never()).postRefund(any(), anyLong(), any());
        verify(refundRepository, never()).save(any());
    }

    @Test
    @DisplayName("a webhook for a payment we have not recorded throws, so it is retried")
    void testWebhookForUnknownPaymentThrows() {
        when(paymentRepository.findIdByRazorpayPaymentId("pay_missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> refundService.handleWebhookRefundProcessed("rfnd_1", "pay_missing", 100L))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("a refund made outside PaySphere is recorded and posted to the ledger")
    void testWebhookRecordsExternalRefund() {
        Payment p = payment();
        stubPayment(p);
        when(refundRepository.findByRazorpayRefundId("rfnd_dash")).thenReturn(Optional.empty());
        when(refundRepository.findByPaymentId(paymentId)).thenReturn(List.of());
        when(refundRepository.save(any(Refund.class))).thenAnswer(i -> i.getArgument(0));

        refundService.handleWebhookRefundProcessed("rfnd_dash", "pay_abc", 25000L);

        ArgumentCaptor<Refund> saved = ArgumentCaptor.forClass(Refund.class);
        verify(refundRepository, atLeastOnce()).save(saved.capture());
        Refund last = saved.getValue();
        assertThat(last.getStatus()).isEqualTo(RefundStatus.PROCESSED);
        assertThat(last.getIdempotencyKey()).isEqualTo("webhook-rfnd_dash");
        assertThat(p.getAmountRefundedPaise()).isEqualTo(25000L);
        verify(ledgerService).postRefund(any(), eq(25000L), eq("INR"));
    }

    @Test
    @DisplayName("a webhook refund larger than the remaining balance is rejected")
    void testWebhookRefundLargerThanBalanceIsRejected() {
        Payment p = payment();
        stubPayment(p);
        when(refundRepository.findByRazorpayRefundId("rfnd_big")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> refundService.handleWebhookRefundProcessed("rfnd_big", "pay_abc", 150000L))
                .isInstanceOf(BusinessException.class);

        verify(ledgerService, never()).postRefund(any(), anyLong(), any());
    }
        private Refund refundWith(Payment p, RefundStatus status, String gatewayId, long amount) {
        return Refund.builder().id(UUID.randomUUID()).payment(p).businessOrder(p.getBusinessOrder())
                .amountPaise(amount).currency("INR").status(status).razorpayRefundId(gatewayId)
                .idempotencyKey("client-" + UUID.randomUUID()).build();
    }

    @Test
    @DisplayName("refund.failed on a PENDING refund marks it FAILED and posts nothing")
    void testFailedWebhookOnPendingRefund() {
        Payment p = payment();
        Refund pending = refundWith(p, RefundStatus.PENDING, null, 30000L);
        stubPayment(p);
        when(refundRepository.findByRazorpayRefundId("rfnd_f1")).thenReturn(Optional.empty());
        when(refundRepository.findByPaymentId(paymentId)).thenReturn(List.of(pending));
        when(refundRepository.save(any(Refund.class))).thenAnswer(i -> i.getArgument(0));

        refundService.handleWebhookRefundFailed("rfnd_f1", "pay_abc", 30000L);

        assertThat(pending.getStatus()).isEqualTo(RefundStatus.FAILED);
        assertThat(pending.getRazorpayRefundId()).isEqualTo("rfnd_f1");
        verify(ledgerService, never()).postRefundReversal(any(), anyLong(), any());
        verify(auditService).record(eq("REFUND_FAILED"), eq("Refund"), eq(pending.getId()),
                anyString(), eq("WEBHOOK"));
    }

    @Test
    @DisplayName("refund.failed on a PROCESSED refund reverses payment, order and ledger")
    void testFailedWebhookReversesProcessedRefund() {
        Payment p = payment();
        p.setAmountRefundedPaise(30000L);
        p.getBusinessOrder().transitionTo(BusinessOrderStatus.PARTIALLY_REFUNDED);
        Refund processed = refundWith(p, RefundStatus.PROCESSED, "rfnd_f2", 30000L);
        stubPayment(p);
        when(refundRepository.findByRazorpayRefundId("rfnd_f2")).thenReturn(Optional.of(processed));
        when(refundRepository.save(any(Refund.class))).thenAnswer(i -> i.getArgument(0));

        refundService.handleWebhookRefundFailed("rfnd_f2", "pay_abc", 30000L);

        assertThat(processed.getStatus()).isEqualTo(RefundStatus.FAILED);
        assertThat(p.getAmountRefundedPaise()).isZero();
        assertThat(p.getBusinessOrder().getStatus()).isEqualTo(BusinessOrderStatus.PAYMENT_CAPTURED);
        verify(ledgerService).postRefundReversal(eq(processed.getId()), eq(30000L), eq("INR"));
    }

    @Test
    @DisplayName("reversing a full refund puts payment and order back to CAPTURED")
    void testFailedWebhookReversesFullRefund() {
        Payment p = payment();
        p.setAmountRefundedPaise(100000L);
        p.setStatus(PaymentStatus.REFUNDED);
        p.getBusinessOrder().transitionTo(BusinessOrderStatus.REFUNDED);
        Refund processed = refundWith(p, RefundStatus.PROCESSED, "rfnd_f3", 100000L);
        stubPayment(p);
        when(refundRepository.findByRazorpayRefundId("rfnd_f3")).thenReturn(Optional.of(processed));
        when(refundRepository.save(any(Refund.class))).thenAnswer(i -> i.getArgument(0));

        refundService.handleWebhookRefundFailed("rfnd_f3", "pay_abc", 100000L);

        assertThat(p.getStatus()).isEqualTo(PaymentStatus.CAPTURED);
        assertThat(p.getAmountRefundedPaise()).isZero();
        assertThat(p.getBusinessOrder().getStatus()).isEqualTo(BusinessOrderStatus.PAYMENT_CAPTURED);
    }

    @Test
    @DisplayName("refund.failed for an already FAILED refund changes nothing")
    void testFailedWebhookIsIdempotent() {
        Payment p = payment();
        Refund failed = refundWith(p, RefundStatus.FAILED, "rfnd_f4", 30000L);
        stubPayment(p);
        when(refundRepository.findByRazorpayRefundId("rfnd_f4")).thenReturn(Optional.of(failed));

        refundService.handleWebhookRefundFailed("rfnd_f4", "pay_abc", 30000L);

        verify(refundRepository, never()).save(any());
        verify(ledgerService, never()).postRefundReversal(any(), anyLong(), any());
        verify(auditService, never()).record(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("refund.failed for a refund we never recorded is ignored without an error")
    void testFailedWebhookForUnknownRefundIsIgnored() {
        Payment p = payment();
        stubPayment(p);
        when(refundRepository.findByRazorpayRefundId("rfnd_x")).thenReturn(Optional.empty());
        when(refundRepository.findByPaymentId(paymentId)).thenReturn(List.of());

        refundService.handleWebhookRefundFailed("rfnd_x", "pay_abc", 1000L);

        verify(refundRepository, never()).save(any());
        verify(ledgerService, never()).postRefundReversal(any(), anyLong(), any());
    }
        @Test
    @DisplayName("a refund without an Idempotency-Key is rejected before anything else happens")
    void testMissingIdempotencyKeyIsRejected() {
        assertThatThrownBy(() -> refundService.createRefund(request(30000L), null))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> refundService.createRefund(request(30000L), "   "))
                .isInstanceOf(InvalidRequestException.class);

        verifyNoInteractions(paymentRepository, refundRepository, paymentGateway);
    }

    @Test
    @DisplayName("a malformed Idempotency-Key is rejected")
    void testMalformedIdempotencyKeyIsRejected() {
        assertThatThrownBy(() -> refundService.createRefund(request(30000L), "short"))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> refundService.createRefund(request(30000L), "has spaces in it!"))
                .isInstanceOf(InvalidRequestException.class);

        verifyNoInteractions(paymentRepository, refundRepository, paymentGateway);
    }

    @Test
    @DisplayName("a gateway 5xx leaves the refund PENDING: the outcome is unknown")
    void testGatewayServerErrorLeavesPending() {
        Payment p = payment();
        when(paymentRepository.findByIdForUpdate(paymentId)).thenReturn(Optional.of(p));
        when(refundRepository.findByIdempotencyKey(anyString())).thenReturn(Optional.empty());
        when(refundRepository.save(any(Refund.class))).thenAnswer(i -> i.getArgument(0));
        when(paymentGateway.createRefund(any(), anyLong(), any(), any()))
                .thenThrow(GatewayException.fromHttpStatus(503, "unavailable"));

        RefundResponse r = refundService.createRefund(request(30000L), "server-err-key-1");

        assertThat(r.status()).isEqualTo(RefundStatus.PENDING);
        verify(ledgerService, never()).postRefund(any(), anyLong(), any());
    }

    @Test
    @DisplayName("a gateway 409 (same request still running) leaves the refund PENDING")
    void testGatewayConflictLeavesPending() {
        Payment p = payment();
        when(paymentRepository.findByIdForUpdate(paymentId)).thenReturn(Optional.of(p));
        when(refundRepository.findByIdempotencyKey(anyString())).thenReturn(Optional.empty());
        when(refundRepository.save(any(Refund.class))).thenAnswer(i -> i.getArgument(0));
        when(paymentGateway.createRefund(any(), anyLong(), any(), any()))
                .thenThrow(GatewayException.fromHttpStatus(409, "request in progress"));

        RefundResponse r = refundService.createRefund(request(30000L), "conflict-key-001");

        assertThat(r.status()).isEqualTo(RefundStatus.PENDING);
    }
}