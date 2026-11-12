package com.paysphere.ledger.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * LedgerEntry — a single debit or credit line in a journal entry.
 *
 * Each LedgerTransaction has 2+ entries that must balance:
 *   SUM(DEBIT entries) == SUM(CREDIT entries)
 *
 * This is enforced by LedgerService before persisting.
 */
@Entity
@Table(name = "ledger_entries")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LedgerEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ledger_transaction_id", nullable = false)
    private LedgerTransaction ledgerTransaction;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ledger_account_id", nullable = false)
    private LedgerAccount ledgerAccount;

    @Enumerated(EnumType.STRING)
    @Column(name = "entry_type", nullable = false, length = 6)
    private EntryType entryType;   // DEBIT or CREDIT

    @Column(name = "amount_paise", nullable = false)
    private Long amountPaise;

    @Column(name = "currency", nullable = false, length = 3)
    @Builder.Default
    private String currency = "INR";

    @Column(name = "description", length = 255)
    private String description;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public enum EntryType {
        DEBIT, CREDIT
    }
}
