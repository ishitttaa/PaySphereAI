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
                .thenThrow(new RuntimeException("Failed to create refund",
                        new java.io.IOException("Razorpay API error: HTTP 400 -> bad request")));

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
}