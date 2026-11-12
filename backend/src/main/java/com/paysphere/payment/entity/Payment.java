package com.paysphere.payment.entity;

import com.paysphere.order.entity.BusinessOrder;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * Payment entity — represents a single payment attempt for a business order.
 *
 * One business order can have multiple payment attempts (e.g., first card
 * declined, then retried with UPI). Only one payment reaches CAPTURED status.
 *
 * SECURITY NOTES:
 * - No card PAN (Primary Account Number) is stored. Ever.
 * - card_last4 and card_network are non-sensitive display fields.
 * - signatureVerified is set to true only after successful HMAC verification.
 * - vpa (UPI Virtual Payment Address) can be stored — it's like an account alias.
 */
@Entity
@Table(name = "payments")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "business_order_id", nullable = false)
    private BusinessOrder businessOrder;

    @Column(name = "razorpay_payment_id", nullable = false, unique = true, length = 100)
    private String razorpayPaymentId;

    @Column(name = "razorpay_order_id", nullable = false, length = 100)
    private String razorpayOrderId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    @Builder.Default
    private PaymentStatus status = PaymentStatus.CREATED;

    @Column(name = "amount_paise", nullable = false)
    private Long amountPaise;

    @Column(name = "amount_refunded_paise", nullable = false)
    @Builder.Default
    private Long amountRefundedPaise = 0L;

    @Column(name = "currency", nullable = false, length = 3)
    @Builder.Default
    private String currency = "INR";

    // ── Payment method details ──────────────────────────────────────────────

    @Column(name = "method", length = 30)
    private String method;   // card, upi, netbanking, wallet

    @Column(name = "bank", length = 100)
    private String bank;

    @Column(name = "wallet", length = 50)
    private String wallet;

    @Column(name = "vpa", length = 255)
    private String vpa;  // UPI VPA — not a secret

    // ── Card details (non-sensitive — no PAN) ──────────────────────────────

    @Column(name = "card_last4", length = 4)
    private String cardLast4;

    @Column(name = "card_network", length = 20)
    private String cardNetwork;  // Visa, Mastercard, RuPay, etc.

    @Column(name = "card_issuer", length = 100)
    private String cardIssuer;

    // ── Failure details ────────────────────────────────────────────────────

    @Column(name = "error_code", length = 100)
    private String errorCode;

    @Column(name = "error_description", length = 500)
    private String errorDescription;

    @Column(name = "error_source", length = 50)
    private String errorSource;

    @Column(name = "error_step", length = 50)
    private String errorStep;

    @Column(name = "error_reason", length = 100)
    private String errorReason;

    // ── Timing ────────────────────────────────────────────────────────────

    @Column(name = "razorpay_created_at")
    private Instant razorpayCreatedAt;

    @Column(name = "razorpay_captured_at")
    private Instant razorpayCapturedAt;

    // ── Verification ──────────────────────────────────────────────────────

    /**
     * True only after we successfully verified the HMAC-SHA256 signature.
     * Payment should not be treated as confirmed until this is true.
     */
    @Column(name = "signature_verified", nullable = false)
    @Builder.Default
    private Boolean signatureVerified = false;

    @Column(name = "international", nullable = false)
    @Builder.Default
    private Boolean international = false;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
