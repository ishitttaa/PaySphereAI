package com.paysphere.payment.entity;

/**
 * Payment status state machine.
 *
 * CREATED → AUTHORIZED → CAPTURED
 *                      → FAILED
 * CAPTURED → REFUNDED
 *
 * Razorpay auto-capture (default) means AUTHORIZED → CAPTURED happens
 * immediately. Manual capture is possible but not configured in M1 scope.
 */
public enum PaymentStatus {
    /** Payment attempt initiated by customer. */
    CREATED,

    /** Razorpay authorized the payment (funds held). Auto-capture not yet run. */
    AUTHORIZED,

    /** Payment captured — money deducted from customer. Held by Razorpay. */
    CAPTURED,

    /** Payment attempt failed (card declined, timeout, etc.) */
    FAILED,

    /** Full refund has been processed. */
    REFUNDED
}
