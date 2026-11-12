package com.paysphere.order.entity;

/**
 * Business order status — the internal state machine for PaySphere orders.
 *
 * State transitions:
 *
 *   PENDING
 *     │
 *     ├─ Razorpay order created → PAYMENT_INITIATED
 *     │       │
 *     │       ├─ Signature verified + captured → PAYMENT_CAPTURED
 *     │       ├─ Payment failed → PAYMENT_FAILED
 *     │       └─ Razorpay order expired → PAYMENT_EXPIRED
 *     │
 *     └─ (direct fail if Razorpay API unreachable) → PAYMENT_FAILED
 *
 * PAYMENT_CAPTURED → REFUNDED (full)
 * PAYMENT_CAPTURED → PARTIALLY_REFUNDED (partial)
 * PARTIALLY_REFUNDED → REFUNDED (remaining amount)
 *
 * Terminal states: PAYMENT_CAPTURED, PAYMENT_FAILED, PAYMENT_EXPIRED, REFUNDED
 */
public enum BusinessOrderStatus {

    /** Order created in PaySphere. Razorpay order not yet created. */
    PENDING,

    /** Razorpay order created. Customer redirected to checkout. */
    PAYMENT_INITIATED,

    /**
     * Payment signature verified + Razorpay confirmed capture.
     * Money has been deducted from customer and held by Razorpay.
     * (Settlement to merchant bank comes later.)
     */
    PAYMENT_CAPTURED,

    /** Payment attempt failed (card declined, bank error, etc.) */
    PAYMENT_FAILED,

    /** Customer abandoned checkout. Razorpay order expired (15 min). */
    PAYMENT_EXPIRED,

    /** Full refund issued back to customer. */
    REFUNDED,

    /** Partial refund issued. Some amount has been returned. */
    PARTIALLY_REFUNDED
}
