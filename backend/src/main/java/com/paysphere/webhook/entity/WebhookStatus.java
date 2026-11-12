package com.paysphere.webhook.entity;

public enum WebhookStatus {
    /** Received and stored, not yet processed. */
    PENDING,

    /** Successfully processed. */
    PROCESSED,

    /** Processing failed — may be retried. */
    FAILED,

    /** Skipped because the event was already processed (idempotency). */
    SKIPPED
}
