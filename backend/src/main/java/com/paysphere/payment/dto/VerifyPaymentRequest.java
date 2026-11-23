package com.paysphere.payment.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * Request DTO for verifying a payment after checkout completes.
 *
 * The values come from the Razorpay checkout callback (or from the local
 * payment simulator, whose ids look like pay_sim_... and order_sim_...).
 *
 * SECURITY: the signature is always verified server-side with HMAC-SHA256.
 * The id format check below only blocks obviously malformed input.
 */
public record VerifyPaymentRequest(

        @NotBlank(message = "razorpay_payment_id is required")
        @Pattern(regexp = "^pay_(?:sim_)?[A-Za-z0-9]+$", message = "Invalid razorpay_payment_id format")
        String razorpayPaymentId,

        @NotBlank(message = "razorpay_order_id is required")
        @Pattern(regexp = "^order_(?:sim_)?[A-Za-z0-9]+$", message = "Invalid razorpay_order_id format")
        String razorpayOrderId,

        @NotBlank(message = "razorpay_signature is required")
        String razorpaySignature
) {}