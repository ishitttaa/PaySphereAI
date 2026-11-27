package com.paysphere.live;

import java.time.Instant;

/** A small "something changed" message for the staff dashboard. No customer data. */
public record LiveEvent(
        String type,        // e.g. PAYMENT_CAPTURED, REFUND_PROCESSED
        String entityId,    // id of the payment, refund or order (may be null)
        Long amountPaise,   // may be null
        Instant occurredAt
) {
    public static LiveEvent of(String type, String entityId, Long amountPaise) {
        return new LiveEvent(type, entityId, amountPaise, Instant.now());
    }
}