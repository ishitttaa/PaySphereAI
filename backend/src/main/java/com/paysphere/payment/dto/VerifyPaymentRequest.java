package com.paysphere.payment.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * Request DTO for verifying a payment after Razorpay checkout completes.
 *
 * The frontend receives these three values from the Razorpay checkout callback:
 *   razorpay_payment_id    → unique ID for this payment attempt
 *   razorpay_order_id      → the order ID we created (matches our business_order)
 *   razorpay_signature     → HMAC-SHA256 we must verify server-side
 *
 * SECURITY: The signature MUST be verified server-side using HMAC-SHA256
 * with your key_secret. Never skip this step — payment_id alone does not
 * prove payment was successful. Razorpay could fabricate a payment_id.
 *
 * These values are identifiers, not secrets. However, they should be
 * transmitted over HTTPS and not logged at INFO level.
 */
public record VerifyPaymentRequest(

        @NotBlank(message = "razorpay_payment_id is required")
        @Pattern(regexp = "^pay_[A-Za-z0-9]+$", message = "Invalid razorpay_payment_id format")
        String razorpayPaymentId,

        @NotBlank(message = "razorpay_order_id is required")
        @Pattern(regexp = "^order_[A-Za-z0-9]+$", message = "Invalid razorpay_order_id format")
        String razorpayOrderId,

        @NotBlank(message = "razorpay_signature is required")
        String razorpaySignature
) {}
