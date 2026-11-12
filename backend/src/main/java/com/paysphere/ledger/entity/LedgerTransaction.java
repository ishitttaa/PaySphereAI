package com.paysphere.ledger.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * LedgerTransaction — a journal entry grouping related LedgerEntries.
 *
 * Invariant: SUM of all entry amounts where type=DEBIT == SUM where type=CREDIT
 * This is enforced by LedgerService when posting entries.
 *
 * The idempotency_key prevents double-posting the same financial event.
 * Format: "{sourceType}-{sourceId}"
 * Example: "PAYMENT-550e8400-e29b-41d4-a716-446655440000"
 */
@Entity
@Table(name = "ledger_transactions")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LedgerTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "source_type", nullable = false, length = 30)
    private String sourceType;   // PAYMENT, REFUND, SETTLEMENT, FEE

    @Column(name = "source_id")
    private UUID sourceId;

    @Column(name = "description", nullable = false, length = 255)
    private String description;

    @Column(name = "idempotency_key", nullable = false, unique = true, length = 100)
    private String idempotencyKey;

    @Column(name = "amount_paise", nullable = false)
    private Long amountPaise;

    @Column(name = "currency", nullable = false, length = 3)
    @Builder.Default
    private String currency = "INR";

    @Column(name = "posted_at", nullable = false)
    private Instant postedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @OneToMany(mappedBy = "ledgerTransaction", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @Builder.Default
    private List<LedgerEntry> entries = new ArrayList<>();
}
