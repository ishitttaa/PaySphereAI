package com.paysphere.payment.dto;

import com.paysphere.payment.entity.PaymentStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * Response DTO for a payment record.
 *
 * SECURITY: No raw card numbers, no key secrets. Only display-safe fields.
 * The signatureVerified flag tells the frontend the payment has been
 * cryptographically confirmed by the backend.
 */
public record PaymentResponse(
        UUID id,
        UUID businessOrderId,
        String razorpayPaymentId,
        String razorpayOrderId,
        PaymentStatus status,
        Long amountPaise,
        Long amountRefundedPaise,
        String currency,

        // Payment method
        String method,
        String bank,
        String wallet,
        String vpa,

        // Non-sensitive card display info
        String cardLast4,
        String cardNetwork,

        // Failure details
        String errorCode,
        String errorDescription,

        // Verification
        Boolean signatureVerified,
        Boolean international,

        // Timestamps
        Instant createdAt,
        Instant updatedAt,
        Instant razorpayCapturedAt
) {}
