package com.paysphere.settlement.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * Settlement — records when Razorpay transfers net funds to the merchant's bank.
 *
 * Razorpay settles funds on a T+2 or T+3 schedule depending on the account type.
 * Settlement data comes via Razorpay webhooks (settlement.processed event).
 *
 * The UTR (Unique Transaction Reference) is the bank transfer reference number —
 * proof that money arrived in the merchant bank account.
 */
@Entity
@Table(name = "settlements")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Settlement {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "razorpay_settlement_id", nullable = false, unique = true, length = 100)
    private String razorpaySettlementId;

    @Column(name = "amount_paise", nullable = false)
    private Long amountPaise;

    @Column(name = "fees_paise", nullable = false)
    @Builder.Default
    private Long feesPaise = 0L;

    @Column(name = "tax_paise", nullable = false)
    @Builder.Default
    private Long taxPaise = 0L;

    @Column(name = "currency", nullable = false, length = 3)
    @Builder.Default
    private String currency = "INR";

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private SettlementStatus status = SettlementStatus.PENDING;

    /**
     * Bank transfer reference number.
     * Proof that money hit the merchant bank account.
     */
    @Column(name = "utr", length = 100)
    private String utr;

    @Column(name = "settled_at")
    private Instant settledAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
