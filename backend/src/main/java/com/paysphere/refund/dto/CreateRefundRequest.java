package com.paysphere.refund.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Request to initiate a refund for a captured payment.
 *
 * Rules enforced server-side:
 * - amountPaise cannot exceed payment's captured amount
 * - paymentId must reference a CAPTURED payment
 * - Partial refunds are allowed (amountPaise < captured amount)
 */
public record CreateRefundRequest(

        @NotNull(message = "paymentId is required")
        UUID paymentId,

        /**
         * Amount to refund in paise.
         * Must be > 0 and <= original payment amount.
         * If not provided (null), a full refund is issued.
         */
        @Min(value = 1, message = "Refund amount must be at least 1 paise")
        Long amountPaise,

        @Size(max = 100, message = "Reason must not exceed 100 characters")
        String reason,

        @Size(max = 500, message = "Notes must not exceed 500 characters")
        String notes
) {}
