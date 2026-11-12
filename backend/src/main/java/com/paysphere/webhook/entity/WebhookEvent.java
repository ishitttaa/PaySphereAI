package com.paysphere.webhook.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * WebhookEvent — stores every incoming Razorpay webhook payload.
 *
 * WHY store raw webhooks?
 * 1. Audit trail: you can always reprocess or investigate past events.
 * 2. Idempotency: razorpay_event_id is unique — prevents double-processing.
 * 3. Retry: PENDING events can be reprocessed if something failed.
 *
 * The payload column stores the complete raw JSON from Razorpay,
 * allowing full reprocessing without any data loss.
 */
@Entity
@Table(name = "webhook_events")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WebhookEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "razorpay_event_id", nullable = false, unique = true, length = 100)
    private String razorpayEventId;

    @Column(name = "event_type", nullable = false, length = 100)
    private String eventType;

    @Column(name = "payload", nullable = false, columnDefinition = "TEXT")
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private WebhookStatus status = WebhookStatus.PENDING;

    @Column(name = "error_message", length = 500)
    private String errorMessage;

    @Column(name = "retry_count", nullable = false)
    @Builder.Default
    private Integer retryCount = 0;

    @Column(name = "signature_verified", nullable = false)
    @Builder.Default
    private Boolean signatureVerified = false;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    @Column(name = "processed_at")
    private Instant processedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
