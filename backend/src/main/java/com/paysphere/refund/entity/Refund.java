package com.paysphere.refund.entity;

import com.paysphere.order.entity.BusinessOrder;
import com.paysphere.payment.entity.Payment;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * Refund entity — represents a refund request for a captured payment.
 *
 * Key rules:
 * - Refund amount cannot exceed the captured amount
 * - Partial refunds are supported
 * - Idempotency key prevents duplicate refund API calls
 * - Status tracks the Razorpay refund lifecycle
 */
@Entity
@Table(name = "refunds")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Refund {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "payment_id", nullable = false)
    private Payment payment;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "business_order_id", nullable = false)
    private BusinessOrder businessOrder;

    @Column(name = "razorpay_refund_id", unique = true, length = 100)
    private String razorpayRefundId;

    @Column(name = "amount_paise", nullable = false)
    private Long amountPaise;

    @Column(name = "currency", nullable = false, length = 3)
    @Builder.Default
    private String currency = "INR";

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    @Builder.Default
    private RefundStatus status = RefundStatus.PENDING;

    @Column(name = "reason", length = 100)
    private String reason;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    /**
     * Prevents duplicate refund API calls.
     * Format: "refund-{orderId}-{amountPaise}-{uuid-partial}"
     */
    @Column(name = "idempotency_key", nullable = false, unique = true, length = 100)
    private String idempotencyKey;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "processed_at")
    private Instant processedAt;
}
