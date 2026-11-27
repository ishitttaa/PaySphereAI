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
import java.util.Optional;
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

        // Idempotency check, with the row locked (rule 8). A FAILED attempt is not "processed":
        // the checkout says it succeeded, so we verify it below and upgrade the row.
        Payment existing = lockByGatewayPaymentId(request.razorpayPaymentId()).orElse(null);
        if (existing != null && existing.getStatus() != PaymentStatus.FAILED) {
            log.info("Payment already processed: {}", request.razorpayPaymentId());
            return toPaymentResponse(existing);
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
        Payment payment = existing != null
            ? upgradeFailedToCaptured(existing, paymentDetails, true)
            : buildPaymentFromRazorpayData(order, request, paymentDetails);
        payment = paymentRepository.save(payment);
        // Step 5: Update the business order status
        order.transitionTo(BusinessOrderStatus.PAYMENT_CAPTURED);
        orderRepository.save(order);

        log.info("Payment captured: paymentId={}, orderRef={}, amount={}p",
                payment.getId(), order.getOrderReference(), payment.getAmountPaise());

        ledgerService.postPaymentCaptured(payment.getId(), payment.getAmountPaise(), payment.getCurrency());
        auditService.record("PAYMENT_CAPTURED", "Payment", payment.getId(),
                                "Captured via checkout verification: orderRef=" + order.getOrderReference()
                        + ", amount=" + payment.getAmountPaise() + "p"
                        + (existing != null ? " (after an earlier failed attempt)" : ""), "CHECKOUT");
        evaluateRiskSafely(payment);
        return toPaymentResponse(payment);
    }

    /**
     * Handle a payment recorded via webhook (M5).
     * Called by WebhookService when a payment.captured event arrives.
     * Idempotent — safe to call multiple times with the same payment ID.
     */
        /** Called by the webhook handler. */
    @Transactional
    public void handleWebhookCapture(String razorpayPaymentId, String razorpayOrderId,
                                    Map<String, Object> paymentData) {
        recordGatewayCapture(razorpayPaymentId, razorpayOrderId, paymentData, "WEBHOOK");
    }

    /**
     * Records a payment the gateway reports as captured: payment row, ledger, audit, risk, order status.
     * Idempotent. Used by the webhook and by reconciliation.
     */
        @Transactional
    public void recordGatewayCapture(String razorpayPaymentId, String razorpayOrderId,
                                     Map<String, Object> paymentData, String source) {
        // Lock first (rule 8). A FAILED attempt is upgraded below; any other existing row means "already recorded".
        Payment existing = lockByGatewayPaymentId(razorpayPaymentId).orElse(null);
        if (existing != null && existing.getStatus() != PaymentStatus.FAILED) {
            log.info("Gateway capture: payment already recorded: {}", razorpayPaymentId);
            return;
        }

        BusinessOrder order = orderRepository.findByRazorpayOrderId(razorpayOrderId)
                .orElse(null);

        if (order == null) {
            log.warn("Gateway capture: no order found for razorpayOrderId={}", razorpayOrderId);
            return;
        }

        long webhookAmount = getLong(paymentData, "amount");
        if (order.getAmountPaise() == null || webhookAmount != order.getAmountPaise()) {
            throw new BusinessException("AMOUNT_MISMATCH",
                    "Gateway amount " + webhookAmount + " does not match order " + order.getOrderReference());
        }

        boolean upgradedFromFailed = existing != null;
        Payment payment;
        if (upgradedFromFailed) {
            // The gateway captured a payment we had recorded as failed (late authorisation or a retry).
            payment = upgradeFailedToCaptured(existing, paymentData, false);
        } else {
            payment = Payment.builder()
                    .businessOrder(order)
                    .razorpayPaymentId(razorpayPaymentId)
                    .razorpayOrderId(razorpayOrderId)
                    .status(PaymentStatus.CAPTURED)
                    .amountPaise(webhookAmount)
                    .currency(getString(paymentData, "currency", "INR"))
                    .method(getString(paymentData, "method", null))
                    .signatureVerified(false)  // webhook/reconciliation does not carry a payment signature
                    .build();
        }

        payment = paymentRepository.save(payment);
        ledgerService.postPaymentCaptured(payment.getId(), payment.getAmountPaise(), payment.getCurrency());

        auditService.record(
                "PAYMENT_CAPTURED",
                "Payment",
                payment.getId(),
                "Captured via " + source.toLowerCase() + ": orderRef=" + order.getOrderReference()
                        + ", amount=" + payment.getAmountPaise() + "p"
                        + (upgradedFromFailed ? " (after an earlier failed attempt)" : ""),
                source
        );

        evaluateRiskSafely(payment);

        if (order.canTransitionTo(BusinessOrderStatus.PAYMENT_CAPTURED)) {
            order.transitionTo(BusinessOrderStatus.PAYMENT_CAPTURED);
            orderRepository.save(order);
        }

        log.info("Gateway capture recorded: source={}, paymentId={}, upgradedFromFailed={}",
                source, razorpayPaymentId, upgradedFromFailed);
    }

        /**
     * Handles a payment failure reported by the gateway (payment.failed webhook).
     * Records the failed attempt (error details + audit) and fails the order if its state allows.
     * A failed attempt never overwrites a payment we already hold: events can arrive out of order,
     * and the gateway can capture a payment it first reported as failed.
     */
    @Transactional
    public void handleWebhookFailure(String razorpayPaymentId, String razorpayOrderId,
                                     Map<String, Object> paymentData) {
        if (razorpayPaymentId == null || razorpayPaymentId.isBlank()
                || razorpayOrderId == null || razorpayOrderId.isBlank()) {
            log.warn("payment.failed webhook without a payment id or order id: ignored");
            return;
        }
        BusinessOrder order = orderRepository.findByRazorpayOrderId(razorpayOrderId).orElse(null);
        if (order == null) {
            log.warn("payment.failed webhook for an unknown order: razorpayOrderId={}", razorpayOrderId);
            return;
        }

        // Lock first (rule 8). Any payment we already hold (CAPTURED, or this same FAILED attempt) is left alone.
        if (lockByGatewayPaymentId(razorpayPaymentId).isPresent()) {
            log.info("payment.failed webhook: payment already recorded, left unchanged: {}", razorpayPaymentId);
            return;
        }

        long amount = getLong(paymentData, "amount");
        if (amount > 0) {
            String currency = optionalText(paymentData, "currency", 3);
            Payment failed = paymentRepository.save(Payment.builder()
                    .businessOrder(order)
                    .razorpayPaymentId(razorpayPaymentId)
                    .razorpayOrderId(razorpayOrderId)
                    .status(PaymentStatus.FAILED)
                    .amountPaise(amount)
                    .currency(currency != null ? currency : order.getCurrency())
                    .method(optionalText(paymentData, "method", 30))
                    .errorCode(optionalText(paymentData, "error_code", 100))
                    .errorDescription(optionalText(paymentData, "error_description", 500))
                    .errorSource(optionalText(paymentData, "error_source", 50))
                    .errorStep(optionalText(paymentData, "error_step", 50))
                    .errorReason(optionalText(paymentData, "error_reason", 100))
                    .signatureVerified(false)  // the webhook HMAC covers the payload, as for captures
                    .build());
            auditService.record("PAYMENT_FAILED", "Payment", failed.getId(),
                    "Failed attempt via webhook: orderRef=" + order.getOrderReference()
                            + ", amount=" + amount + "p, reason=" + failed.getErrorReason(),
                    "WEBHOOK");
        } else {
            log.warn("payment.failed webhook without a usable amount; attempt not recorded: {}", razorpayPaymentId);
        }

        // The order state machine decides: a captured or refunded order is never failed.
        if (order.canTransitionTo(BusinessOrderStatus.PAYMENT_FAILED)) {
            order.transitionTo(BusinessOrderStatus.PAYMENT_FAILED);
            orderRepository.save(order);
        }
        log.info("Payment failure recorded via webhook: paymentId={}", razorpayPaymentId);
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
    /** Finds the payment row for a gateway payment id and locks it. Id-only lookup first, so no stale copy is read before the lock. */
    private Optional<Payment> lockByGatewayPaymentId(String razorpayPaymentId) {
        return paymentRepository.findIdByRazorpayPaymentId(razorpayPaymentId)
                .flatMap(paymentRepository::findByIdForUpdate);
    }
        /** Turns a FAILED attempt into a CAPTURED payment using the gateway's data, and clears the old error details. */
    private Payment upgradeFailedToCaptured(Payment failed, Map<String, Object> data, boolean signatureVerified) {
        failed.setStatus(PaymentStatus.CAPTURED);
        failed.setAmountPaise(getLong(data, "amount"));
        failed.setCurrency(getString(data, "currency", "INR"));
        failed.setMethod(getString(data, "method", failed.getMethod()));
        failed.setSignatureVerified(signatureVerified);
        if (data.get("international") instanceof Boolean intl) {
            failed.setInternational(intl);
        }
        failed.setErrorCode(null);
        failed.setErrorDescription(null);
        failed.setErrorSource(null);
        failed.setErrorStep(null);
        failed.setErrorReason(null);
        return failed;
    }
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
        /** Text from a gateway payload: null if missing, blank, or JSON null; cut to the column size. */
    private String optionalText(Map<String, Object> map, String key, int maxLength) {
        Object val = map.get(key);
        if (val == null) {
            return null;
        }
        String text = val.toString().trim();
        if (text.isEmpty() || "null".equals(text)) {
            return null;
        }
        return text.length() > maxLength ? text.substring(0, maxLength) : text;
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
