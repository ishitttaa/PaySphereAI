package com.paysphere.payment.service;
import com.paysphere.audit.service.AuditService;
import com.paysphere.risk.service.RiskService;
import com.paysphere.common.exception.BusinessException;
import com.paysphere.ledger.service.LedgerService;
import com.paysphere.common.exception.ResourceNotFoundException;
import com.paysphere.common.exception.SignatureVerificationException;
import com.paysphere.gateway.PaymentGateway;
import com.paysphere.order.entity.BusinessOrder;
import com.paysphere.order.entity.BusinessOrderStatus;
import com.paysphere.order.repository.BusinessOrderRepository;
import com.paysphere.payment.dto.PaymentResponse;
import com.paysphere.payment.dto.VerifyPaymentRequest;
import com.paysphere.payment.entity.Payment;
import com.paysphere.payment.entity.PaymentStatus;
import com.paysphere.payment.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * PaymentService — Handles payment verification and lifecycle management.
 *
 * M3: Checkout + server-side signature verification
 * M4: Payment lifecycle state machine
 *
 * The verify flow (M3):
 * 1. Frontend sends razorpay_payment_id, razorpay_order_id, razorpay_signature
 * 2. We verify HMAC-SHA256 signature using key_secret (server-side only)
 * 3. If invalid → reject with SignatureVerificationException
 * 4. If valid → fetch full payment details from Razorpay API
 * 5. Create/update Payment record in DB
 * 6. Transition BusinessOrder to PAYMENT_CAPTURED
 * 7. Post ledger entries (double-entry bookkeeping)
 * 8. Return payment details to frontend
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {
    private final RiskService riskService;
    private final AuditService auditService;
    private final PaymentRepository paymentRepository;
    private final BusinessOrderRepository orderRepository;
    private final PaymentGateway paymentGateway;
    private final LedgerService ledgerService;
    /**
     * Verify and capture a payment from Razorpay checkout callback.
     *
     * This method is idempotent: if the same payment_id is submitted twice,
     * we return the existing payment record rather than creating a duplicate.
     *
     * @Transactional ensures that the Payment record and BusinessOrder status
     * update happen atomically. Both succeed or both roll back.
     */
    @Transactional
    public PaymentResponse verifyAndCapture(VerifyPaymentRequest request) {
        log.info("Verifying payment: razorpayPaymentId={}, razorpayOrderId={}",
                request.razorpayPaymentId(), request.razorpayOrderId());

        // Idempotency check — if we already processed this payment, return it
        if (paymentRepository.existsByRazorpayPaymentId(request.razorpayPaymentId())) {
            log.info("Payment already processed: {}", request.razorpayPaymentId());
            return paymentRepository.findByRazorpayPaymentId(request.razorpayPaymentId())
                    .map(this::toPaymentResponse)
                    .orElseThrow();
        }

        // Step 1: Find the business order by Razorpay order ID
        BusinessOrder order = orderRepository
                .findByRazorpayOrderId(request.razorpayOrderId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Order not found for razorpay_order_id: " + request.razorpayOrderId()));

        // Step 2: Verify the signature FIRST — reject before any DB writes on failure
        boolean signatureValid = paymentGateway.verifyPaymentSignature(
                request.razorpayOrderId(),
                request.razorpayPaymentId(),
                request.razorpaySignature()
        );

        if (!signatureValid) {
            // Log only the order ID, never the signature itself
            log.warn("SECURITY: Invalid payment signature for orderId={}, orderRef={}",
                    request.razorpayOrderId(), order.getOrderReference());
            throw new SignatureVerificationException(
                    "Payment signature verification failed for order: " + order.getOrderReference());
        }

        log.info("Signature verified for orderId={}", request.razorpayOrderId());

        // Step 3: Fetch full payment details from Razorpay
        Map<String, Object> paymentDetails = paymentGateway.fetchPayment(request.razorpayPaymentId());
        // Step 3b: Trust Razorpay's record, not the browser.
        long expectedAmount = order.getAmountPaise();
        long paidAmount = getLong(paymentDetails, "amount");
        String paidCurrency = getString(paymentDetails, "currency", "");
        if (paidAmount != expectedAmount || !order.getCurrency().equals(paidCurrency)) {
            log.error("SECURITY: amount mismatch for orderRef={}: expected {}p {}, Razorpay reports {}p {}",
                    order.getOrderReference(), expectedAmount, order.getCurrency(), paidAmount, paidCurrency);
            throw new BusinessException("AMOUNT_MISMATCH", "Paid amount does not match the order amount");
        }
        if (!Boolean.TRUE.equals(paymentDetails.get("captured"))) {
            throw new BusinessException("PAYMENT_NOT_CAPTURED",
                    "Payment is not captured yet (status: " + paymentDetails.get("status") + ")");
        }
        // Step 4: Create the Payment record in our DB
        Payment payment = buildPaymentFromRazorpayData(order, request, paymentDetails);
        payment = paymentRepository.save(payment);

        // Step 5: Update the business order status
        order.transitionTo(BusinessOrderStatus.PAYMENT_CAPTURED);
        orderRepository.save(order);

        log.info("Payment captured: paymentId={}, orderRef={}, amount={}p",
                payment.getId(), order.getOrderReference(), payment.getAmountPaise());

        ledgerService.postPaymentCaptured(payment.getId(), payment.getAmountPaise(), payment.getCurrency());
        auditService.record("PAYMENT_CAPTURED", "Payment", payment.getId(),
                "Captured via checkout verification: orderRef=" + order.getOrderReference()
                        + ", amount=" + payment.getAmountPaise() + "p", "CHECKOUT");
        evaluateRiskSafely(payment);
        return toPaymentResponse(payment);
    }

    /**
     * Handle a payment recorded via webhook (M5).
     * Called by WebhookService when a payment.captured event arrives.
     * Idempotent — safe to call multiple times with the same payment ID.
     */
    @Transactional
    public void handleWebhookCapture(String razorpayPaymentId, String razorpayOrderId,
                                      Map<String, Object> paymentData) {
        if (paymentRepository.existsByRazorpayPaymentId(razorpayPaymentId)) {
            log.info("Webhook: payment already recorded: {}", razorpayPaymentId);
            return;
        }

        BusinessOrder order = orderRepository.findByRazorpayOrderId(razorpayOrderId)
                .orElse(null);

        if (order == null) {
            log.warn("Webhook: no order found for razorpayOrderId={}", razorpayOrderId);
            return;
        }
        long webhookAmount = getLong(paymentData, "amount");
        if (order.getAmountPaise() == null || webhookAmount != order.getAmountPaise()) {
            throw new BusinessException("AMOUNT_MISMATCH",
                    "Webhook amount " + webhookAmount + " does not match order " + order.getOrderReference());
        }
        Payment payment = Payment.builder()
                .businessOrder(order)
                .razorpayPaymentId(razorpayPaymentId)
                .razorpayOrderId(razorpayOrderId)
                .status(PaymentStatus.CAPTURED)
                .amountPaise(getLong(paymentData, "amount"))
                .currency(getString(paymentData, "currency", "INR"))
                .method(getString(paymentData, "method", null))
                .signatureVerified(false)  // webhook doesn't carry payment signature
                .build();

        payment=paymentRepository.save(payment);
        ledgerService.postPaymentCaptured(payment.getId(), payment.getAmountPaise(), payment.getCurrency());
        auditService.record("PAYMENT_CAPTURED", "Payment", payment.getId(),
        "Captured via checkout verification: orderRef=" + order.getOrderReference()
                + ", amount=" + payment.getAmountPaise() + "p", "CHECKOUT");
        evaluateRiskSafely(payment);
        if (order.canTransitionTo(BusinessOrderStatus.PAYMENT_CAPTURED)) {
            order.transitionTo(BusinessOrderStatus.PAYMENT_CAPTURED);
            orderRepository.save(order);
        }

        log.info("Webhook payment captured and recorded: paymentId={}", razorpayPaymentId);
    }

    /** Handle a payment failure via webhook. */
    @Transactional
    public void handleWebhookFailure(String razorpayPaymentId, String razorpayOrderId,
                                      Map<String, Object> paymentData) {
        BusinessOrder order = orderRepository.findByRazorpayOrderId(razorpayOrderId)
                .orElse(null);
        if (order == null) return;

        // Only transition if not already in a terminal state
        if (order.canTransitionTo(BusinessOrderStatus.PAYMENT_FAILED)) {
            order.transitionTo(BusinessOrderStatus.PAYMENT_FAILED);
            orderRepository.save(order);
        }
        log.info("Payment failed recorded via webhook: paymentId={}", razorpayPaymentId);
    }

    /** Get payment by ID. */
    @Transactional(readOnly = true)
    public PaymentResponse getPaymentById(UUID paymentId) {
        return paymentRepository.findById(paymentId)
                .map(this::toPaymentResponse)
                .orElseThrow(() -> new ResourceNotFoundException("Payment", paymentId));
    }

    /** Get payment by Razorpay payment ID. */
    @Transactional(readOnly = true)
    public PaymentResponse getPaymentByRazorpayId(String razorpayPaymentId) {
        return paymentRepository.findByRazorpayPaymentId(razorpayPaymentId)
                .map(this::toPaymentResponse)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Payment not found: " + razorpayPaymentId));
    }

    /** Get all payments for a business order. */
    @Transactional(readOnly = true)
    public List<PaymentResponse> getPaymentsForOrder(UUID orderId) {
        return paymentRepository.findByBusinessOrderId(orderId).stream()
                .map(this::toPaymentResponse)
                .collect(Collectors.toList());
    }

    /** List all payments paginated. */
    @Transactional(readOnly = true)
    public Page<PaymentResponse> listPayments(Pageable pageable) {
        return paymentRepository.findAllByOrderByCreatedAtDesc(pageable)
                .map(this::toPaymentResponse);
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private Payment buildPaymentFromRazorpayData(BusinessOrder order,
                                                   VerifyPaymentRequest request,
                                                   Map<String, Object> data) {
        return Payment.builder()
                .businessOrder(order)
                .razorpayPaymentId(request.razorpayPaymentId())
                .razorpayOrderId(request.razorpayOrderId())
                .status(PaymentStatus.CAPTURED)
                .amountPaise(getLong(data, "amount"))
                .currency(getString(data, "currency", "INR"))
                .method(getString(data, "method", null))
                .signatureVerified(true)
                .international((Boolean) data.getOrDefault("international", false))
                .build();
    }

    private PaymentResponse toPaymentResponse(Payment p) {
        return new PaymentResponse(
                p.getId(),
                p.getBusinessOrder() != null ? p.getBusinessOrder().getId() : null,
                p.getRazorpayPaymentId(),
                p.getRazorpayOrderId(),
                p.getStatus(),
                p.getAmountPaise(),
                p.getAmountRefundedPaise(),
                p.getCurrency(),
                p.getMethod(),
                p.getBank(),
                p.getWallet(),
                p.getVpa(),
                p.getCardLast4(),
                p.getCardNetwork(),
                p.getErrorCode(),
                p.getErrorDescription(),
                p.getSignatureVerified(),
                p.getInternational(),
                p.getCreatedAt(),
                p.getUpdatedAt(),
                p.getRazorpayCapturedAt()
        );
    }

    private Long getLong(Map<String, Object> map, String key) {
        Object val = map.get(key);
        if (val instanceof Long l) return l;
        if (val instanceof Integer i) return i.longValue();
        if (val instanceof Number n) return n.longValue();
        return 0L;
    }
    private void evaluateRiskSafely(Payment payment) {
    try {
        riskService.evaluatePayment(payment);
    } catch (RuntimeException e) {
        log.error("Risk evaluation failed for payment {}: {}", payment.getId(), e.getMessage());
    }
    }
    private String getString(Map<String, Object> map, String key, String defaultValue) {
        Object val = map.get(key);
        return val != null ? val.toString() : defaultValue;
    }
}
