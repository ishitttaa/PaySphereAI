package com.paysphere.refund.dto;

import com.paysphere.refund.entity.RefundStatus;

import java.time.Instant;
import java.util.UUID;

public record RefundResponse(
        UUID id,
        UUID paymentId,
        UUID businessOrderId,
        String razorpayRefundId,
        Long amountPaise,
        String currency,
        RefundStatus status,
        String reason,
        Instant createdAt,
        Instant processedAt
) {}
