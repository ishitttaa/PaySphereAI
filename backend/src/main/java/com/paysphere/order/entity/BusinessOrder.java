package com.paysphere.order.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * BusinessOrder — PaySphere's internal record of a payment intent.
 *
 * This is created BEFORE calling Razorpay. If the Razorpay API call fails,
 * we still have a local record showing PENDING status.
 *
 * Key design decisions:
 * - amountPaise: stored as integer PAISE, not rupees (avoids floating-point)
 * - razorpayOrderId: null until Razorpay call succeeds
 * - The amount here is the AUTHORITATIVE amount — Razorpay is configured with
 *   this exact amount. The frontend cannot modify it.
 */
@Entity
@Table(name = "business_orders")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BusinessOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "order_reference", nullable = false, unique = true, length = 50)
    private String orderReference;

    @Column(name = "product_id", nullable = false, length = 100)
    private String productId;

    @Column(name = "product_name", nullable = false, length = 255)
    private String productName;

    /**
     * Amount in paise. This is the AUTHORITATIVE amount.
     * Read from the products table, never from the client request.
     */
    @Column(name = "amount_paise", nullable = false)
    private Long amountPaise;

    @Column(name = "currency", nullable = false, length = 3)
    @Builder.Default
    private String currency = "INR";

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    @Builder.Default
    private BusinessOrderStatus status = BusinessOrderStatus.PENDING;

    /**
     * Set after successfully calling Razorpay POST /v1/orders.
     * Null until then.
     */
    @Column(name = "razorpay_order_id", unique = true, length = 100)
    private String razorpayOrderId;

    @Column(name = "customer_name", length = 100)
    private String customerName;

    @Column(name = "customer_email", length = 255)
    private String customerEmail;

    @Column(name = "customer_phone", length = 20)
    private String customerPhone;

    @Column(name = "created_by_user_id")
    private UUID createdByUserId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "expires_at")
    private Instant expiresAt;

    // ── Domain methods ────────────────────────────────────────────────────────

    /**
     * Checks if this order can transition to the target status.
     * Enforces the state machine transitions.
     */
    public boolean canTransitionTo(BusinessOrderStatus targetStatus) {
        return switch (this.status) {
            case PENDING -> targetStatus == BusinessOrderStatus.PAYMENT_INITIATED
                         || targetStatus == BusinessOrderStatus.PAYMENT_FAILED;
            case PAYMENT_INITIATED -> targetStatus == BusinessOrderStatus.PAYMENT_CAPTURED
                                   || targetStatus == BusinessOrderStatus.PAYMENT_FAILED
                                   || targetStatus == BusinessOrderStatus.PAYMENT_EXPIRED;
            case PAYMENT_CAPTURED -> targetStatus == BusinessOrderStatus.REFUNDED
                                  || targetStatus == BusinessOrderStatus.PARTIALLY_REFUNDED;
            case PARTIALLY_REFUNDED -> targetStatus == BusinessOrderStatus.REFUNDED
                                    || targetStatus == BusinessOrderStatus.PAYMENT_CAPTURED;  // wound back if a refund FAILED
            case REFUNDED -> targetStatus == BusinessOrderStatus.PARTIALLY_REFUNDED
                          || targetStatus == BusinessOrderStatus.PAYMENT_CAPTURED;            // wound back if a refund FAILED
            case PAYMENT_EXPIRED -> targetStatus == BusinessOrderStatus.PAYMENT_CAPTURED;  // a late payment must never be lost
            case PAYMENT_FAILED -> targetStatus == BusinessOrderStatus.PAYMENT_CAPTURED;  // a failed attempt does not end the order: the customer can retry
            default -> false;  // Terminal states: REFUNDED, PAYMENT_FAILED, PAYMENT_EXPIRED
        };
    }

    /**
     * Transition to a new status, enforcing state machine rules.
     *
     * @throws IllegalStateException if the transition is invalid
     */
    public void transitionTo(BusinessOrderStatus targetStatus) {
        if (!canTransitionTo(targetStatus)) {
            throw new IllegalStateException(
                    "Invalid status transition for order " + orderReference +
                    ": " + this.status + " → " + targetStatus
            );
        }
        this.status = targetStatus;
    }

    /** Returns true if the order has reached a terminal state. */
    public boolean isTerminal() {
        return switch (this.status) {
            case PAYMENT_CAPTURED, PAYMENT_FAILED, PAYMENT_EXPIRED, REFUNDED -> true;
            default -> false;
        };
    }
}
