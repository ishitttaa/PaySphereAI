package com.paysphere.ledger.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * LedgerAccount — represents an account in the double-entry ledger.
 *
 * Double-entry bookkeeping requires every financial event to have
 * equal debits and credits. The accounts are:
 *
 * ASSET accounts    (increase with DEBIT,  decrease with CREDIT)
 * LIABILITY         (increase with CREDIT, decrease with DEBIT)
 * REVENUE           (increase with CREDIT, decrease with DEBIT)
 * EXPENSE           (increase with DEBIT,  decrease with CREDIT)
 *
 * When a payment is captured:
 *   DR RAZORPAY_RECEIVABLE   (we expect Razorpay to settle this)
 *   CR REVENUE               (we earned the money)
 *
 * When Razorpay settles to our bank:
 *   DR MERCHANT_BANK         (money arrived in bank)
 *   CR RAZORPAY_RECEIVABLE   (no longer owed to us by Razorpay)
 *   DR RAZORPAY_FEES         (fees we paid)
 *   CR RAZORPAY_RECEIVABLE   (deducted from receivable)
 */
@Entity
@Table(name = "ledger_accounts")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LedgerAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "code", nullable = false, unique = true, length = 20)
    private String code;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "account_type", nullable = false, length = 20)
    private LedgerAccountType accountType;

    @Column(name = "description", length = 255)
    private String description;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
