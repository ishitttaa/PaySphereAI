package com.paysphere.refund.service;

import com.paysphere.audit.service.AuditService;
import com.paysphere.common.exception.BusinessException;
import com.paysphere.common.exception.ResourceNotFoundException;
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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * RefundService: creates refunds and applies their outcome.
 *
 * Rules enforced:
 * 1. Only CAPTURED payments can be refunded.
 * 2. Refund amount cannot exceed the remaining balance.
 * 3. The same Idempotency-Key returns the earlier refund instead of refunding twice.
 * 4. The payment row is locked, so two refunds on one payment never run together.
 * 5. If the outcome is uncertain (timeout, network error) the refund stays PENDING.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RefundService {

    private final RefundRepository refundRepository;
    private final PaymentRepository paymentRepository;
    private final BusinessOrderRepository orderRepository;
    private final PaymentGateway paymentGateway;
    private final LedgerService ledgerService;
    private final AuditService auditService;

    @Transactional
    public RefundResponse createRefund(CreateRefundRequest request) {
        return createRefund(request, null);
    }

    @Transactional
    public RefundResponse createRefund(CreateRefundRequest request, String clientIdempotencyKey) {
        log.info("Creating refund for paymentId={}, amount={}p",
                request.paymentId(), request.amountPaise());

        // Step 0: Decide the idempotency key.
        String idempotencyKey;
        if (clientIdempotencyKey != null && !clientIdempotencyKey.isBlank()) {
            if (!clientIdempotencyKey.matches("^[A-Za-z0-9_-]{8,64}$")) {
                throw new BusinessException("INVALID_IDEMPOTENCY_KEY",
                        "Idempotency-Key must be 8-64 characters: letters, digits, '-' or '_'");
            }
            idempotencyKey = "client-" + clientIdempotencyKey;
        } else {
            log.warn("Refund requested without Idempotency-Key: duplicate submissions are NOT protected");
            idempotencyKey = "server-" + UUID.randomUUID();
        }

        // Step 1: Load the payment and LOCK its row.
        Payment payment = paymentRepository.findByIdForUpdate(request.paymentId())
                .orElseThrow(() -> new ResourceNotFoundException("Payment", request.paymentId()));

        // Step 1b: Same key seen before? Return the earlier refund.
        Refund existing = refundRepository.findByIdempotencyKey(idempotencyKey).orElse(null);
        if (existing != null) {
            if (!existing.getPayment().getId().equals(payment.getId())) {
                throw new BusinessException("IDEMPOTENCY_KEY_REUSED",
                        "This Idempotency-Key was already used for a different payment");
            }
            log.info("Duplicate refund request, returning existing refund {}", existing.getId());
            return toRefundResponse(existing);
        }

        if (payment.getStatus() != PaymentStatus.CAPTURED) {
            throw new BusinessException("INVALID_PAYMENT_STATUS",
                    "Can only refund CAPTURED payments. Current status: " + payment.getStatus());
        }

        // Step 2: Work out the amount. No amount given = refund whatever is left.
        long alreadyRefunded = payment.getAmountRefundedPaise();
        long remainingBalance = payment.getAmountPaise() - alreadyRefunded;
        long refundAmount = request.amountPaise() != null ? request.amountPaise() : remainingBalance;

        if (refundAmount <= 0 || refundAmount > remainingBalance) {
            throw new BusinessException("REFUND_EXCEEDS_BALANCE",
                    String.format("Refund amount %dp is not allowed. Remaining balance %dp",
                            refundAmount, remainingBalance));
        }

        // Step 3: Save the refund as PENDING before calling the gateway.
        BusinessOrder order = payment.getBusinessOrder();
        Refund refund = Refund.builder()
                .payment(payment)
                .businessOrder(order)
                .amountPaise(refundAmount)
                .currency(payment.getCurrency())
                .status(RefundStatus.PENDING)
                .reason(request.reason())
                .notes(request.notes())
                .idempotencyKey(idempotencyKey)
                .build();
        refund = refundRepository.save(refund);

        // Step 4: Call the gateway. Only the network call sits inside this try.
        Map<String, Object> razorpayRefund = null;
        try {
            razorpayRefund = paymentGateway.createRefund(
                    payment.getRazorpayPaymentId(), refundAmount, request.reason(), idempotencyKey);
        } catch (Exception e) {
            if (isDefinitiveFailure(e)) {
                log.error("Refund rejected by gateway: refundId={}, error={}", refund.getId(), e.getMessage());
                refund.setStatus(RefundStatus.FAILED);
            } else {
                // Timeout or network error: the gateway may or may not have refunded.
                // Do NOT mark FAILED. Leave PENDING for the webhook / reconciliation.
                log.error("Refund outcome UNKNOWN, left PENDING: refundId={}, error={}",
                        refund.getId(), e.getMessage());
            }
        }

        // Step 5: Apply the outcome.
        if (razorpayRefund != null) {
            refund = applyProcessedRefund(refund, payment, (String) razorpayRefund.get("id"), "API");
        } else {
            refund = refundRepository.save(refund);
            auditService.record("REFUND_" + refund.getStatus(), "Refund", refund.getId(),
                    "Refund of " + refund.getAmountPaise() + "p on payment " + payment.getId(), "API");
        }

        return toRefundResponse(refund);
    }
        /**
     * Handles a "refund.processed" webhook. Safe to receive twice, and safe to race with createRefund.
     * Throws when the refund cannot be applied, so the webhook is recorded FAILED and retried.
     */
    @Transactional
    public void handleWebhookRefundProcessed(String razorpayRefundId, String razorpayPaymentId, long amountPaise) {
        UUID paymentId = paymentRepository.findIdByRazorpayPaymentId(razorpayPaymentId)
                .orElseThrow(() -> new IllegalStateException(
                        "Refund webhook for a payment not recorded yet: " + razorpayPaymentId));

        // Lock the payment row FIRST, then read refund state, so we never act on stale data.
        Payment payment = paymentRepository.findByIdForUpdate(paymentId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment", paymentId));

        Refund known = refundRepository.findByRazorpayRefundId(razorpayRefundId).orElse(null);
        if (known != null && known.getStatus() == RefundStatus.PROCESSED) {
            log.info("Refund webhook already applied, skipping: {}", razorpayRefundId);
            return;
        }
        if (known != null && !known.getAmountPaise().equals(amountPaise)) {
            throw new BusinessException("REFUND_AMOUNT_MISMATCH",
                    "Refund " + razorpayRefundId + " amount differs from our record");
        }

        long remaining = payment.getAmountPaise() - payment.getAmountRefundedPaise();
        if (payment.getStatus() != PaymentStatus.CAPTURED || amountPaise > remaining) {
            throw new BusinessException("REFUND_NOT_APPLICABLE",
                    "Refund " + razorpayRefundId + " of " + amountPaise + "p cannot be applied: payment status "
                            + payment.getStatus() + ", remaining balance " + remaining + "p");
        }

        Refund target = known;
        if (target == null) {
            // A refund that timed out on our side stays PENDING without a gateway id.
            // Match it by amount, oldest first.
            target = refundRepository.findByPaymentId(paymentId).stream()
                    .filter(r -> r.getStatus() == RefundStatus.PENDING
                            && r.getRazorpayRefundId() == null
                            && r.getAmountPaise() == amountPaise)
                    .min(Comparator.comparing(Refund::getCreatedAt,
                            Comparator.nullsLast(Comparator.naturalOrder())))
                    .orElse(null);
        }
        if (target == null) {
            // A refund made outside PaySphere (for example from the gateway dashboard).
            // The money has left, so our books must show it.
            target = Refund.builder()
                    .payment(payment)
                    .businessOrder(payment.getBusinessOrder())
                    .amountPaise(amountPaise)
                    .currency(payment.getCurrency())
                    .status(RefundStatus.PENDING)
                    .reason("Refund created outside PaySphere")
                    .idempotencyKey("webhook-" + razorpayRefundId)
                    .build();
        }

        applyProcessedRefund(target, payment, razorpayRefundId, "WEBHOOK");
    }
        /**
     * Handles a "refund.failed" webhook (the gateway could not process a refund).
     *  - PENDING refund: mark it FAILED (nothing was posted, so nothing to undo).
     *  - PROCESSED refund (already in the ledger): undo it with a REVERSING ledger entry and
     *    restore the payment and the order. Ledger rows are never edited.
     *  - A refund we never recorded: nothing of ours to undo.
     */
    @Transactional
    public void handleWebhookRefundFailed(String razorpayRefundId, String razorpayPaymentId, long amountPaise) {
        UUID paymentId = paymentRepository.findIdByRazorpayPaymentId(razorpayPaymentId).orElse(null);
        if (paymentId == null) {
            log.warn("refund.failed for a payment we never recorded: {}", razorpayPaymentId);
            return;
        }

        // Lock the payment row FIRST, then read refund state.
        Payment payment = paymentRepository.findByIdForUpdate(paymentId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment", paymentId));

        Refund refund = refundRepository.findByRazorpayRefundId(razorpayRefundId).orElse(null);
        if (refund == null) {
            refund = refundRepository.findByPaymentId(paymentId).stream()
                    .filter(r -> r.getStatus() == RefundStatus.PENDING
                            && r.getRazorpayRefundId() == null
                            && r.getAmountPaise() == amountPaise)
                    .min(Comparator.comparing(Refund::getCreatedAt,
                            Comparator.nullsLast(Comparator.naturalOrder())))
                    .orElse(null);
        }
        if (refund == null) {
            log.info("refund.failed for a refund we never recorded, nothing to undo: {}", razorpayRefundId);
            return;
        }
        if (refund.getStatus() == RefundStatus.FAILED) {
            log.info("refund.failed already applied, skipping: {}", razorpayRefundId);
            return;
        }

        if (refund.getStatus() == RefundStatus.PROCESSED) {
            reverseProcessedRefund(refund, payment);
        } else {
            if (refund.getRazorpayRefundId() == null) {
                refund.setRazorpayRefundId(razorpayRefundId);
            }
            refund.setStatus(RefundStatus.FAILED);
            refundRepository.save(refund);
        }

        auditService.record("REFUND_FAILED", "Refund", refund.getId(),
                "Gateway reported refund " + razorpayRefundId + " failed (" + amountPaise + "p)", "WEBHOOK");
    }

    /** Undoes a refund we had already booked: payment total, statuses, and a reversing ledger entry. */
    private void reverseProcessedRefund(Refund refund, Payment payment) {
        long newTotal = Math.max(0L, payment.getAmountRefundedPaise() - refund.getAmountPaise());
        payment.setAmountRefundedPaise(newTotal);
        if (payment.getStatus() == PaymentStatus.REFUNDED) {
            payment.setStatus(PaymentStatus.CAPTURED);
        }

        BusinessOrder order = payment.getBusinessOrder();
        BusinessOrderStatus target = newTotal == 0
                ? BusinessOrderStatus.PAYMENT_CAPTURED : BusinessOrderStatus.PARTIALLY_REFUNDED;
        if (order.getStatus() != target && order.canTransitionTo(target)) {
            order.transitionTo(target);
            orderRepository.save(order);
        }
        paymentRepository.save(payment);

        refund.setStatus(RefundStatus.FAILED);
        refundRepository.save(refund);
        ledgerService.postRefundReversal(refund.getId(), refund.getAmountPaise(), refund.getCurrency());

        log.warn("Refund {} failed at the gateway after being booked: reversed in payment, order and ledger",
                refund.getId());
    }
    /**
     * Applies a refund the gateway has CONFIRMED: marks it PROCESSED, updates the payment's
     * refunded total and the order status, writes the audit entry and posts the ledger entry.
     *
     * Shared by createRefund and (next step) the refund webhook, so both behave identically.
     * Must run inside a transaction, with the payment row already locked by the caller.
     */
    public Refund applyProcessedRefund(Refund refund, Payment payment,
                                       String razorpayRefundId, String actor) {
        refund.setRazorpayRefundId(razorpayRefundId);
        refund.setStatus(RefundStatus.PROCESSED);
        refund.setProcessedAt(Instant.now());

        long newTotalRefunded = payment.getAmountRefundedPaise() + refund.getAmountPaise();
        payment.setAmountRefundedPaise(newTotalRefunded);

        BusinessOrder order = payment.getBusinessOrder();
        if (newTotalRefunded >= payment.getAmountPaise()) {
            payment.setStatus(PaymentStatus.REFUNDED);
            if (order.canTransitionTo(BusinessOrderStatus.REFUNDED)) {
                order.transitionTo(BusinessOrderStatus.REFUNDED);
                orderRepository.save(order);
            }
        } else if (order.canTransitionTo(BusinessOrderStatus.PARTIALLY_REFUNDED)) {
            order.transitionTo(BusinessOrderStatus.PARTIALLY_REFUNDED);
            orderRepository.save(order);
        }
        paymentRepository.save(payment);

        Refund saved = refundRepository.save(refund);
        log.info("Refund processed: refundId={}, razorpayRefundId={}, amount={}p",
                saved.getId(), razorpayRefundId, saved.getAmountPaise());

        auditService.record("REFUND_PROCESSED", "Refund", saved.getId(),
                "Refund of " + saved.getAmountPaise() + "p on payment " + payment.getId(), actor);
        ledgerService.postRefund(saved.getId(), saved.getAmountPaise(), saved.getCurrency());
        return saved;
    }

    /** A clear 4xx from the gateway means nothing was refunded. Anything else is uncertain. */
    private boolean isDefinitiveFailure(Exception e) {
        Throwable cause = e.getCause() != null ? e.getCause() : e;
        String msg = cause.getMessage() == null ? "" : cause.getMessage();
        return msg.startsWith("Razorpay API error: HTTP 4")
                && !msg.startsWith("Razorpay API error: HTTP 409");
    }

    /** Get all refunds for a payment. */
    @Transactional(readOnly = true)
    public List<RefundResponse> getRefundsForPayment(UUID paymentId) {
        return refundRepository.findByPaymentId(paymentId).stream()
                .map(this::toRefundResponse)
                .collect(Collectors.toList());
    }

    /** Get all refunds for a business order. */
    @Transactional(readOnly = true)
    public List<RefundResponse> getRefundsForOrder(UUID orderId) {
        return refundRepository.findByBusinessOrderId(orderId).stream()
                .map(this::toRefundResponse)
                .collect(Collectors.toList());
    }

    /** Get a refund by ID. */
    @Transactional(readOnly = true)
    public RefundResponse getRefundById(UUID refundId) {
        return refundRepository.findById(refundId)
                .map(this::toRefundResponse)
                .orElseThrow(() -> new ResourceNotFoundException("Refund", refundId));
    }

    private RefundResponse toRefundResponse(Refund r) {
        return new RefundResponse(
                r.getId(),
                r.getPayment() != null ? r.getPayment().getId() : null,
                r.getBusinessOrder() != null ? r.getBusinessOrder().getId() : null,
                r.getRazorpayRefundId(),
                r.getAmountPaise(),
                r.getCurrency(),
                r.getStatus(),
                r.getReason(),
                r.getCreatedAt(),
                r.getProcessedAt()
        );
    }
}