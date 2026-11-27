package com.paysphere.payment;

import com.paysphere.audit.service.AuditService;
import com.paysphere.risk.service.RiskService;
import com.paysphere.payment.entity.Payment;

import com.paysphere.common.exception.BusinessException;
import com.paysphere.gateway.PaymentGateway;
import com.paysphere.ledger.service.LedgerService;
import com.paysphere.order.entity.BusinessOrder;
import com.paysphere.order.entity.BusinessOrderStatus;
import com.paysphere.order.repository.BusinessOrderRepository;
import com.paysphere.payment.dto.VerifyPaymentRequest;
import com.paysphere.payment.repository.PaymentRepository;
import com.paysphere.payment.service.PaymentService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.ArgumentCaptor;
import com.paysphere.payment.entity.PaymentStatus;
import java.util.UUID;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    @Mock private RiskService riskService;
    @Mock private AuditService auditService;
    @Mock private PaymentRepository paymentRepository;
    @Mock private BusinessOrderRepository orderRepository;
    @Mock private PaymentGateway paymentGateway;
    @Mock private LedgerService ledgerService;
    @InjectMocks private PaymentService paymentService;

    private BusinessOrder order() {
        return BusinessOrder.builder()
                .orderReference("ORD-1")
                .amountPaise(99900L)
                .currency("INR")
                .status(BusinessOrderStatus.PAYMENT_INITIATED)
                .build();
    }

    private void stubUntilFetch(Map<String, Object> razorpayPayment) {
        when(paymentRepository.existsByRazorpayPaymentId("pay_abc")).thenReturn(false);
        when(orderRepository.findByRazorpayOrderId("order_abc")).thenReturn(Optional.of(order()));
        when(paymentGateway.verifyPaymentSignature("order_abc", "pay_abc", "sig")).thenReturn(true);
        when(paymentGateway.fetchPayment("pay_abc")).thenReturn(razorpayPayment);
    }

    @Test
    @DisplayName("verifyAndCapture rejects a payment whose amount differs from the order")
    void testAmountMismatchRejected() {
        stubUntilFetch(Map.of("amount", 100L, "currency", "INR", "captured", true, "status", "captured"));

        assertThatThrownBy(() -> paymentService.verifyAndCapture(
                new VerifyPaymentRequest("pay_abc", "order_abc", "sig")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("amount");

        verify(paymentRepository, never()).save(any());
        verify(ledgerService, never()).postPaymentCaptured(any(), anyLong(), any());
    }

    @Test
    @DisplayName("verifyAndCapture rejects a payment Razorpay has not captured")
    void testNotCapturedRejected() {
        stubUntilFetch(Map.of("amount", 99900L, "currency", "INR", "captured", false, "status", "authorized"));

        assertThatThrownBy(() -> paymentService.verifyAndCapture(
                new VerifyPaymentRequest("pay_abc", "order_abc", "sig")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("not captured");

        verify(paymentRepository, never()).save(any());
        verify(ledgerService, never()).postPaymentCaptured(any(), anyLong(), any());
    }
    @Test
    @DisplayName("successful capture posts ledger, writes audit, and runs risk rules")
    void testSuccessPostsLedgerAuditAndRisk() {
        stubUntilFetch(Map.of("amount", 99900L, "currency", "INR",
                "captured", true, "status", "captured", "international", true));
        when(paymentRepository.save(any(Payment.class))).thenAnswer(i -> i.getArgument(0));

        paymentService.verifyAndCapture(new VerifyPaymentRequest("pay_abc", "order_abc", "sig"));

        verify(ledgerService).postPaymentCaptured(any(), eq(99900L), eq("INR"));
        verify(auditService).record(eq("PAYMENT_CAPTURED"), eq("Payment"), any(), anyString(), eq("CHECKOUT"));
        ArgumentCaptor<Payment> captor = ArgumentCaptor.forClass(Payment.class);
        verify(riskService).evaluatePayment(captor.capture());
        assertThat(captor.getValue().getInternational()).isTrue();
    }
    
    @Test
    @DisplayName("a gateway capture upgrades an earlier FAILED attempt instead of inserting a second row")
    void captureUpgradesAFailedAttempt() {
        UUID paymentId = UUID.randomUUID();
        Payment failed = Payment.builder()
                .id(paymentId)
                .razorpayPaymentId("pay_late")
                .razorpayOrderId("order_abc")
                .status(PaymentStatus.FAILED)
                .amountPaise(99900L)
                .errorCode("BAD_REQUEST_ERROR")
                .errorDescription("Payment failed")
                .build();
        BusinessOrder failedOrder = order();
        failedOrder.setStatus(BusinessOrderStatus.PAYMENT_FAILED);
        when(paymentRepository.findIdByRazorpayPaymentId("pay_late")).thenReturn(Optional.of(paymentId));
        when(paymentRepository.findByIdForUpdate(paymentId)).thenReturn(Optional.of(failed));
        when(orderRepository.findByRazorpayOrderId("order_abc")).thenReturn(Optional.of(failedOrder));
        when(paymentRepository.save(any(Payment.class))).thenAnswer(i -> i.getArgument(0));

        paymentService.recordGatewayCapture("pay_late", "order_abc",
                Map.of("amount", 99900L, "currency", "INR", "method", "upi"), "WEBHOOK");

        assertThat(failed.getStatus()).isEqualTo(PaymentStatus.CAPTURED);
        assertThat(failed.getErrorCode()).isNull();
        assertThat(failed.getMethod()).isEqualTo("upi");
        assertThat(failedOrder.getStatus()).isEqualTo(BusinessOrderStatus.PAYMENT_CAPTURED);
        verify(paymentRepository).save(failed);
        verify(ledgerService).postPaymentCaptured(eq(paymentId), eq(99900L), eq("INR"));
        verify(auditService).record(eq("PAYMENT_CAPTURED"), eq("Payment"), eq(paymentId),
                contains("after an earlier failed attempt"), eq("WEBHOOK"));
    }

    @Test
    @DisplayName("a gateway capture for a payment that is already CAPTURED changes nothing")
    void captureLeavesACapturedPaymentAlone() {
        UUID paymentId = UUID.randomUUID();
        Payment captured = Payment.builder()
                .id(paymentId)
                .razorpayPaymentId("pay_done")
                .razorpayOrderId("order_abc")
                .status(PaymentStatus.CAPTURED)
                .amountPaise(99900L)
                .build();
        when(paymentRepository.findIdByRazorpayPaymentId("pay_done")).thenReturn(Optional.of(paymentId));
        when(paymentRepository.findByIdForUpdate(paymentId)).thenReturn(Optional.of(captured));

        paymentService.recordGatewayCapture("pay_done", "order_abc",
                Map.of("amount", 99900L, "currency", "INR"), "WEBHOOK");

        assertThat(captured.getStatus()).isEqualTo(PaymentStatus.CAPTURED);
        verify(paymentRepository, never()).save(any());
        verifyNoInteractions(ledgerService, auditService, riskService);
    }

    @Test
    @DisplayName("a gateway capture with no earlier row inserts a new CAPTURED payment")
    void captureInsertsWhenThereIsNoRow() {
        when(paymentRepository.findIdByRazorpayPaymentId("pay_new")).thenReturn(Optional.empty());
        when(orderRepository.findByRazorpayOrderId("order_abc")).thenReturn(Optional.of(order()));
        when(paymentRepository.save(any(Payment.class))).thenAnswer(i -> i.getArgument(0));

        paymentService.recordGatewayCapture("pay_new", "order_abc",
                Map.of("amount", 99900L, "currency", "INR"), "RECONCILIATION");

        ArgumentCaptor<Payment> saved = ArgumentCaptor.forClass(Payment.class);
        verify(paymentRepository).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(PaymentStatus.CAPTURED);
        assertThat(saved.getValue().getRazorpayPaymentId()).isEqualTo("pay_new");
        verify(paymentRepository, never()).findByIdForUpdate(any());
        verify(ledgerService).postPaymentCaptured(any(), eq(99900L), eq("INR"));
    }

    @Test
    @DisplayName("a capture with the wrong amount is rejected and the FAILED row stays FAILED")
    void captureWithWrongAmountLeavesTheFailedRowAlone() {
        UUID paymentId = UUID.randomUUID();
        Payment failed = Payment.builder()
                .id(paymentId)
                .razorpayPaymentId("pay_late")
                .razorpayOrderId("order_abc")
                .status(PaymentStatus.FAILED)
                .amountPaise(99900L)
                .errorCode("BAD_REQUEST_ERROR")
                .build();
        when(paymentRepository.findIdByRazorpayPaymentId("pay_late")).thenReturn(Optional.of(paymentId));
        when(paymentRepository.findByIdForUpdate(paymentId)).thenReturn(Optional.of(failed));
        when(orderRepository.findByRazorpayOrderId("order_abc")).thenReturn(Optional.of(order()));

        assertThatThrownBy(() -> paymentService.recordGatewayCapture("pay_late", "order_abc",
                Map.of("amount", 100L, "currency", "INR"), "WEBHOOK"))
                .isInstanceOf(BusinessException.class);

        assertThat(failed.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(failed.getErrorCode()).isEqualTo("BAD_REQUEST_ERROR");
        verify(paymentRepository, never()).save(any());
        verifyNoInteractions(ledgerService);
    }
}