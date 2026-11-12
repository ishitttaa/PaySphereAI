package com.paysphere.order.dto;

import com.paysphere.order.entity.BusinessOrderStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * Response DTO for a business order.
 *
 * Contains the Razorpay order ID and public key ID needed for
 * the frontend to initialize Razorpay Checkout.
 *
 * SECURITY: Never include key_secret in this response.
 * keyId (public key) is safe to return — it's only used to identify
 * your merchant account to Razorpay's JS library.
 */
public record OrderResponse(

        UUID id,
        String orderReference,
        String productId,
        String productName,
        Long amountPaise,
        String currency,
        BusinessOrderStatus status,

        /** Razorpay order ID — needed by frontend to initiate checkout. */
        String razorpayOrderId,

        /**
         * Razorpay public key ID — needed by frontend to load the Razorpay
         * checkout widget. This is NOT the secret key.
         */
        String razorpayKeyId,

        String customerName,
        String customerEmail,
        Instant createdAt,
        Instant expiresAt
) {}
