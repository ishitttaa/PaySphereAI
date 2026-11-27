package com.paysphere.payment;

import com.paysphere.audit.service.AuditService;
import com.paysphere.risk.service.RiskService;
import com.paysphere.payment.entity.Payment;
import com.paysphere.common.exception.SignatureVerificationException;
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
import org.json.JSONObject;

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
    
    @Test
    @DisplayName("verifyAndCapture upgrades an earlier FAILED attempt and marks the signature verified")
    void verifyUpgradesAFailedAttempt() {
        UUID paymentId = UUID.randomUUID();
        Payment failed = Payment.builder().id(paymentId).razorpayPaymentId("pay_abc")
                .razorpayOrderId("order_abc").status(PaymentStatus.FAILED).amountPaise(99900L)
                .errorCode("BAD_REQUEST_ERROR").build();
        BusinessOrder failedOrder = order();
        failedOrder.setStatus(BusinessOrderStatus.PAYMENT_FAILED);
        when(paymentRepository.findIdByRazorpayPaymentId("pay_abc")).thenReturn(Optional.of(paymentId));
        when(paymentRepository.findByIdForUpdate(paymentId)).thenReturn(Optional.of(failed));
        when(orderRepository.findByRazorpayOrderId("order_abc")).thenReturn(Optional.of(failedOrder));
        when(paymentGateway.verifyPaymentSignature("order_abc", "pay_abc", "sig")).thenReturn(true);
        when(paymentGateway.fetchPayment("pay_abc")).thenReturn(
                Map.of("amount", 99900L, "currency", "INR", "captured", true, "status", "captured"));
        when(paymentRepository.save(any(Payment.class))).thenAnswer(i -> i.getArgument(0));

        paymentService.verifyAndCapture(new VerifyPaymentRequest("pay_abc", "order_abc", "sig"));

        assertThat(failed.getStatus()).isEqualTo(PaymentStatus.CAPTURED);
        assertThat(failed.getSignatureVerified()).isTrue();
        assertThat(failed.getErrorCode()).isNull();
        assertThat(failedOrder.getStatus()).isEqualTo(BusinessOrderStatus.PAYMENT_CAPTURED);
        verify(paymentRepository).save(failed);
        verify(ledgerService).postPaymentCaptured(eq(paymentId), eq(99900L), eq("INR"));
        verify(auditService).record(eq("PAYMENT_CAPTURED"), eq("Payment"), eq(paymentId),
                contains("after an earlier failed attempt"), eq("CHECKOUT"));
    }

    @Test
    @DisplayName("verifyAndCapture on an already CAPTURED payment returns it without touching gateway, ledger or audit")
    void verifyIsIdempotentForACapturedPayment() {
        UUID paymentId = UUID.randomUUID();
        Payment captured = Payment.builder().id(paymentId).razorpayPaymentId("pay_abc")
                .razorpayOrderId("order_abc").status(PaymentStatus.CAPTURED).amountPaise(99900L).build();
        when(paymentRepository.findIdByRazorpayPaymentId("pay_abc")).thenReturn(Optional.of(paymentId));
        when(paymentRepository.findByIdForUpdate(paymentId)).thenReturn(Optional.of(captured));

        paymentService.verifyAndCapture(new VerifyPaymentRequest("pay_abc", "order_abc", "sig"));

        verify(paymentRepository, never()).save(any());
        verifyNoInteractions(paymentGateway, ledgerService, auditService, riskService);
    }

    @Test
    @DisplayName("verifyAndCapture rejects an uncaptured payment and the FAILED row stays FAILED")
    void verifyRejectsAnUncapturedPaymentAndKeepsTheFailedRow() {
        UUID paymentId = UUID.randomUUID();
        Payment failed = Payment.builder().id(paymentId).razorpayPaymentId("pay_abc")
                .razorpayOrderId("order_abc").status(PaymentStatus.FAILED).amountPaise(99900L).build();
        stubUntilFetch(Map.of("amount", 99900L, "currency", "INR", "captured", false, "status", "authorized"));
        when(paymentRepository.findIdByRazorpayPaymentId("pay_abc")).thenReturn(Optional.of(paymentId));
        when(paymentRepository.findByIdForUpdate(paymentId)).thenReturn(Optional.of(failed));

        assertThatThrownBy(() -> paymentService.verifyAndCapture(
                new VerifyPaymentRequest("pay_abc", "order_abc", "sig")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("not captured");

        assertThat(failed.getStatus()).isEqualTo(PaymentStatus.FAILED);
        verify(paymentRepository, never()).save(any());
        verifyNoInteractions(ledgerService);
    }

    @Test
    @DisplayName("a forged signature cannot upgrade a FAILED payment")
    void aForgedSignatureCannotUpgradeAFailedPayment() {
        UUID paymentId = UUID.randomUUID();
        Payment failed = Payment.builder().id(paymentId).razorpayPaymentId("pay_abc")
                .razorpayOrderId("order_abc").status(PaymentStatus.FAILED).amountPaise(99900L).build();
        when(paymentRepository.findIdByRazorpayPaymentId("pay_abc")).thenReturn(Optional.of(paymentId));
        when(paymentRepository.findByIdForUpdate(paymentId)).thenReturn(Optional.of(failed));
        when(orderRepository.findByRazorpayOrderId("order_abc")).thenReturn(Optional.of(order()));
        when(paymentGateway.verifyPaymentSignature("order_abc", "pay_abc", "forged")).thenReturn(false);

        assertThatThrownBy(() -> paymentService.verifyAndCapture(
                new VerifyPaymentRequest("pay_abc", "order_abc", "forged")))
                .isInstanceOf(SignatureVerificationException.class);

        assertThat(failed.getStatus()).isEqualTo(PaymentStatus.FAILED);
        verify(paymentGateway, never()).fetchPayment(any());
        verify(paymentRepository, never()).save(any());
    }
    
    private void stubSaveAssigningId(UUID id) {
        when(paymentRepository.save(any(Payment.class))).thenAnswer(i -> {
            Payment p = i.getArgument(0);
            p.setId(id);
            return p;
        });
    }

    @Test
    @DisplayName("payment.failed records the attempt with its error details, audits it, and fails the order")
    void failureRecordsTheAttempt() {
        UUID id = UUID.randomUUID();
        BusinessOrder o = order();
        when(orderRepository.findByRazorpayOrderId("order_abc")).thenReturn(Optional.of(o));
        stubSaveAssigningId(id);

        paymentService.handleWebhookFailure("pay_f1", "order_abc", Map.of(
                "amount", 99900L, "currency", "INR", "method", "card",
                "error_code", "BAD_REQUEST_ERROR", "error_description", "Payment failed",
                "error_source", "bank", "error_step", "payment_authorization",
                "error_reason", "payment_failed"));

        ArgumentCaptor<Payment> saved = ArgumentCaptor.forClass(Payment.class);
        verify(paymentRepository).save(saved.capture());
        Payment p = saved.getValue();
        assertThat(p.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(p.getAmountPaise()).isEqualTo(99900L);
        assertThat(p.getMethod()).isEqualTo("card");
        assertThat(p.getErrorCode()).isEqualTo("BAD_REQUEST_ERROR");
        assertThat(p.getErrorReason()).isEqualTo("payment_failed");
        assertThat(o.getStatus()).isEqualTo(BusinessOrderStatus.PAYMENT_FAILED);
        verify(auditService).record(eq("PAYMENT_FAILED"), eq("Payment"), eq(id), anyString(), eq("WEBHOOK"));
        verifyNoInteractions(ledgerService, riskService);
    }

    @Test
    @DisplayName("payment.failed never touches a payment that is already CAPTURED")
    void failureNeverDowngradesACapturedPayment() {
        UUID id = UUID.randomUUID();
        Payment captured = Payment.builder().id(id).razorpayPaymentId("pay_c1")
                .razorpayOrderId("order_abc").status(PaymentStatus.CAPTURED).amountPaise(99900L).build();
        when(orderRepository.findByRazorpayOrderId("order_abc")).thenReturn(Optional.of(order()));
        when(paymentRepository.findIdByRazorpayPaymentId("pay_c1")).thenReturn(Optional.of(id));
        when(paymentRepository.findByIdForUpdate(id)).thenReturn(Optional.of(captured));

        paymentService.handleWebhookFailure("pay_c1", "order_abc", Map.of("amount", 99900L, "error_code", "X"));

        assertThat(captured.getStatus()).isEqualTo(PaymentStatus.CAPTURED);
        verify(paymentRepository, never()).save(any());
        verify(orderRepository, never()).save(any());
        verifyNoInteractions(auditService, ledgerService);
    }

    @Test
    @DisplayName("payment.failed for an attempt we already hold as FAILED adds nothing")
    void failureForAnAttemptWeAlreadyHoldAddsNothing() {
        UUID id = UUID.randomUUID();
        Payment failed = Payment.builder().id(id).razorpayPaymentId("pay_f1")
                .razorpayOrderId("order_abc").status(PaymentStatus.FAILED).amountPaise(99900L).build();
        when(orderRepository.findByRazorpayOrderId("order_abc")).thenReturn(Optional.of(order()));
        when(paymentRepository.findIdByRazorpayPaymentId("pay_f1")).thenReturn(Optional.of(id));
        when(paymentRepository.findByIdForUpdate(id)).thenReturn(Optional.of(failed));

        paymentService.handleWebhookFailure("pay_f1", "order_abc", Map.of("amount", 99900L));

        verify(paymentRepository, never()).save(any());
        verifyNoInteractions(auditService);
    }

    @Test
    @DisplayName("payment.failed for an unknown order is ignored")
    void failureForAnUnknownOrderIsIgnored() {
        when(orderRepository.findByRazorpayOrderId("order_x")).thenReturn(Optional.empty());

        paymentService.handleWebhookFailure("pay_f1", "order_x", Map.of("amount", 100L));

        verify(paymentRepository, never()).save(any());
        verifyNoInteractions(auditService);
    }

    @Test
    @DisplayName("payment.failed without a payment id is ignored")
    void failureWithoutAPaymentIdIsIgnored() {
        paymentService.handleWebhookFailure("", "order_abc", Map.of());

        verifyNoInteractions(paymentRepository, orderRepository, auditService);
    }

    @Test
    @DisplayName("JSON null error fields are stored as null, not as the text 'null'")
    void failureStoresJsonNullAsNull() {
        when(orderRepository.findByRazorpayOrderId("order_abc")).thenReturn(Optional.of(order()));
        stubSaveAssigningId(UUID.randomUUID());

        paymentService.handleWebhookFailure("pay_f2", "order_abc", Map.of(
                "amount", 99900L, "error_code", JSONObject.NULL, "error_source", JSONObject.NULL));

        ArgumentCaptor<Payment> saved = ArgumentCaptor.forClass(Payment.class);
        verify(paymentRepository).save(saved.capture());
        assertThat(saved.getValue().getErrorCode()).isNull();
        assertThat(saved.getValue().getErrorSource()).isNull();
    }

    @Test
    @DisplayName("a failed attempt on an order that is already CAPTURED is recorded but the order stays CAPTURED")
    void failureOnAnAlreadyPaidOrderLeavesTheOrderAlone() {
        BusinessOrder paid = order();
        paid.setStatus(BusinessOrderStatus.PAYMENT_CAPTURED);
        when(orderRepository.findByRazorpayOrderId("order_abc")).thenReturn(Optional.of(paid));
        stubSaveAssigningId(UUID.randomUUID());

        paymentService.handleWebhookFailure("pay_f3", "order_abc", Map.of("amount", 99900L));

        assertThat(paid.getStatus()).isEqualTo(BusinessOrderStatus.PAYMENT_CAPTURED);
        verify(paymentRepository).save(any(Payment.class));
        verify(orderRepository, never()).save(any());
    }

    @Test
    @DisplayName("payment.failed without a usable amount is not recorded, but the order still fails")
    void failureWithoutAnAmountStillFailsTheOrder() {
        BusinessOrder o = order();
        when(orderRepository.findByRazorpayOrderId("order_abc")).thenReturn(Optional.of(o));

        paymentService.handleWebhookFailure("pay_f4", "order_abc", Map.of("error_code", "X"));

        verify(paymentRepository, never()).save(any());
        verifyNoInteractions(auditService);
        assertThat(o.getStatus()).isEqualTo(BusinessOrderStatus.PAYMENT_FAILED);
        verify(orderRepository).save(o);
    }
}