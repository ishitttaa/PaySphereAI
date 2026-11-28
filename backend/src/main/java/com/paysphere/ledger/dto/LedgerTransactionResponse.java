package com.paysphere.ledger.dto;

import com.paysphere.ledger.entity.LedgerTransaction;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * What the API returns for a ledger transaction.
 *
 * The JPA entities point at each other (transaction -> entries -> transaction), so returning them
 * directly makes the JSON recurse without end. This DTO is flat and shows only what a reader needs:
 * no idempotency key and no internal links.
 *
 * Build it inside a transaction (the entries and accounts are loaded lazily).
 */
public record LedgerTransactionResponse(
        UUID id,
        String sourceType,
        UUID sourceId,
        String description,
        Long amountPaise,
        String currency,
        Instant postedAt,
        List<Entry> entries
) {

    public record Entry(
            UUID id,
            String entryType,      // DEBIT or CREDIT
            Long amountPaise,
            String currency,
            String description,
            String accountCode,
            String accountName
    ) {}

    public static LedgerTransactionResponse from(LedgerTransaction t) {
        List<Entry> entries = t.getEntries().stream()
                .map(e -> new Entry(
                        e.getId(),
                        e.getEntryType().name(),
                        e.getAmountPaise(),
                        e.getCurrency(),
                        e.getDescription(),
                        e.getLedgerAccount().getCode(),
                        e.getLedgerAccount().getName()))
                .toList();
        return new LedgerTransactionResponse(
                t.getId(),
                t.getSourceType(),
                t.getSourceId(),
                t.getDescription(),
                t.getAmountPaise(),
                t.getCurrency(),
                t.getPostedAt(),
                entries);
    }
}