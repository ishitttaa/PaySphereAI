package com.paysphere.refund.service;
import com.paysphere.ledger.service.LedgerService;
import com.paysphere.common.exception.BusinessException;
import com.paysphere.common.exception.ResourceNotFoundException;
import com.paysphere.gateway.PaymentGateway;
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

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * RefundService — Orchestrates the refund creation and lifecycle.
 *
 * Business rules enforced:
 * 1. Only CAPTURED payments can be refunded
 * 2. Refund amount cannot exceed remaining balance (captured - already_refunded)
 * 3. Idempotency: duplicate refund requests return the existing refund
 * 4. Partial refunds update the order to PARTIALLY_REFUNDED
 * 5. Full refunds update the order to REFUNDED
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
    /**
     * Initiate a refund for a captured payment.
     *
     * Flow:
     * 1. Validate payment is CAPTURED
     * 2. Validate refund amount doesn't exceed remaining balance
     * 3. Create refund record in DB (PENDING)
     * 4. Call Razorpay refund API
     * 5. Update refund status and payment's amountRefundedPaise
     * 6. Transition order status
     */
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

        // Step 1: Load the payment and LOCK its row, so two refund requests
        // for the same payment run one after the other, never together.
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

        // Step 3: Save the refund as PENDING before calling Razorpay.
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

        // Step 4: Call Razorpay. Only the network call sits inside this try.
        Map<String, Object> razorpayRefund = null;
        try {
            razorpayRefund = paymentGateway.createRefund(
                    payment.getRazorpayPaymentId(), refundAmount, request.reason(), idempotencyKey);
        } catch (Exception e) {
            if (isDefinitiveFailure(e)) {
                log.error("Refund rejected by Razorpay: refundId={}, error={}", refund.getId(), e.getMessage());
                refund.setStatus(RefundStatus.FAILED);
            } else {
                // Timeout or network error: Razorpay may or may not have refunded.
                // Do NOT mark FAILED. Leave PENDING for the webhook / reconciliation.
                log.error("Refund outcome UNKNOWN, left PENDING: refundId={}, error={}",
                        refund.getId(), e.getMessage());
            }
        }

        // Step 5: Razorpay accepted it: update refund, payment and order.
        if (razorpayRefund != null) {
            refund.setRazorpayRefundId((String) razorpayRefund.get("id"));
            refund.setStatus(RefundStatus.PROCESSED);
            refund.setProcessedAt(Instant.now());

            long newTotalRefunded = alreadyRefunded + refundAmount;
            payment.setAmountRefundedPaise(newTotalRefunded);
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

            log.info("Refund processed: refundId={}, razorpayRefundId={}, amount={}p",
                    refund.getId(), refund.getRazorpayRefundId(), refundAmount);
        }

        refund = refundRepository.save(refund);

        // Step 6: Ledger entry only once the money has really been refunded.
        if (refund.getStatus() == RefundStatus.PROCESSED) {
            ledgerService.postRefund(refund.getId(), refund.getAmountPaise(), refund.getCurrency());
        }

        return toRefundResponse(refund);
    }

    /** A clear 4xx from Razorpay means nothing was refunded. Anything else is uncertain. */
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
