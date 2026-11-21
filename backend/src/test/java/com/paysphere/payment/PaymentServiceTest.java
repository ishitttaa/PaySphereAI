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

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
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
}