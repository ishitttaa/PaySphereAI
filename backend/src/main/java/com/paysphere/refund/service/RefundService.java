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
        log.info("Creating refund for paymentId={}, amount={}p",
                request.paymentId(), request.amountPaise());

        // Step 1: Load and validate the payment
        Payment payment = paymentRepository.findById(request.paymentId())
                .orElseThrow(() -> new ResourceNotFoundException("Payment", request.paymentId()));

        if (payment.getStatus() != PaymentStatus.CAPTURED) {
            throw new BusinessException("INVALID_PAYMENT_STATUS",
                    "Can only refund CAPTURED payments. Current status: " + payment.getStatus());
        }

        // Step 2: Determine refund amount
        long refundAmount = request.amountPaise() != null
                ? request.amountPaise()
                : payment.getAmountPaise();  // Full refund if amount not specified

        long alreadyRefunded = payment.getAmountRefundedPaise();
        long remainingBalance = payment.getAmountPaise() - alreadyRefunded;

        if (refundAmount > remainingBalance) {
            throw new BusinessException("REFUND_EXCEEDS_BALANCE",
                    String.format("Refund amount %dp exceeds remaining balance %dp",
                            refundAmount, remainingBalance));
        }

        // Step 3: Build idempotency key
        String idempotencyKey = "refund-" + payment.getId() + "-" + refundAmount + "-"
                + UUID.randomUUID().toString().substring(0, 8);

        // Step 4: Create DB record (PENDING)
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

        // Step 5: Call Razorpay
        try {
            Map<String, Object> razorpayRefund = paymentGateway.createRefund(
                    payment.getRazorpayPaymentId(),
                    refundAmount,
                    request.reason(),
                    idempotencyKey
            );

            String razorpayRefundId = (String) razorpayRefund.get("id");
            refund.setRazorpayRefundId(razorpayRefundId);
            refund.setStatus(RefundStatus.PROCESSED);
            refund.setProcessedAt(Instant.now());

            // Update payment's refunded amount
            payment.setAmountRefundedPaise(alreadyRefunded + refundAmount);
            paymentRepository.save(payment);

            // Transition order status
            long newTotalRefunded = alreadyRefunded + refundAmount;
            if (newTotalRefunded >= payment.getAmountPaise()) {
                payment.setStatus(PaymentStatus.REFUNDED);
                paymentRepository.save(payment);
                if (order.canTransitionTo(BusinessOrderStatus.REFUNDED)) {
                    order.transitionTo(BusinessOrderStatus.REFUNDED);
                    orderRepository.save(order);
                }
            } else {
                // Partial refund
                if (order.canTransitionTo(BusinessOrderStatus.PARTIALLY_REFUNDED)) {
                    order.transitionTo(BusinessOrderStatus.PARTIALLY_REFUNDED);
                    orderRepository.save(order);
                }
            }

            log.info("Refund processed: refundId={}, razorpayRefundId={}, amount={}p",
                    refund.getId(), razorpayRefundId, refundAmount);

        } catch (Exception e) {
            log.error("Refund API call failed: {}", e.getMessage(), e);
            refund.setStatus(RefundStatus.FAILED);
        }

        refund = refundRepository.save(refund);
        if (refund.getStatus() == RefundStatus.PROCESSED) {
            ledgerService.postRefund(refund.getId(), refund.getAmountPaise(), refund.getCurrency());
        }
        return toRefundResponse(refund);
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
