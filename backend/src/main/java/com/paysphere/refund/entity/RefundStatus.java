package com.paysphere.refund.entity;

public enum RefundStatus {
    /** Refund requested, not yet sent to Razorpay. */
    PENDING,

    /** Razorpay has processed the refund. Money returned to customer. */
    PROCESSED,

    /** Refund failed — check error details. */
    FAILED
}
