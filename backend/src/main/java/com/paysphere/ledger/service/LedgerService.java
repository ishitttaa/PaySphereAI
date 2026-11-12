package com.paysphere.ledger.service;

import com.paysphere.ledger.entity.LedgerAccount;
import com.paysphere.ledger.entity.LedgerEntry;
import com.paysphere.ledger.entity.LedgerEntry.EntryType;
import com.paysphere.ledger.entity.LedgerTransaction;
import com.paysphere.ledger.repository.LedgerAccountRepository;
import com.paysphere.ledger.repository.LedgerTransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * LedgerService — Posts double-entry journal entries for financial events.
 *
 * Double-entry rule: for every transaction, total debits == total credits.
 * This is enforced by validateBalance() before any entries are persisted.
 *
 * Account codes used (seeded in V4 migration):
 *   RAZORPAY_RECEIVABLE  — funds Razorpay owes us
 *   MERCHANT_BANK        — our actual bank account
 *   REVENUE              — payment income
 *   REFUNDS_PAYABLE      — refunds owed to customers
 *   RAZORPAY_FEES        — gateway fees paid
 *
 * Journal entries for payment captured:
 *   DR  RAZORPAY_RECEIVABLE  [amount]   (asset increases — Razorpay owes us)
 *   CR  REVENUE              [amount]   (revenue recognized)
 *
 * Journal entries for refund:
 *   DR  REFUNDS_PAYABLE      [amount]   (liability — we owe customer)
 *   CR  RAZORPAY_RECEIVABLE  [amount]   (reduces what Razorpay owes us)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LedgerService {

    private final LedgerTransactionRepository txRepo;
    private final LedgerAccountRepository accountRepo;

    /**
     * Post ledger entries for a captured payment.
     * Idempotent: if already posted for this paymentId, does nothing.
     */
    @Transactional
    public void postPaymentCaptured(UUID paymentId, long amountPaise, String currency) {
        String idempotencyKey = "PAYMENT-" + paymentId;
        if (txRepo.existsByIdempotencyKey(idempotencyKey)) {
            log.info("Ledger: payment already posted, skipping: {}", paymentId);
            return;
        }

        LedgerAccount receivable = getAccount("RAZORPAY_RECEIVABLE");
        LedgerAccount revenue    = getAccount("REVENUE");

        LedgerTransaction tx = LedgerTransaction.builder()
                .sourceType("PAYMENT")
                .sourceId(paymentId)
                .description("Payment captured: " + paymentId)
                .idempotencyKey(idempotencyKey)
                .amountPaise(amountPaise)
                .currency(currency)
                .postedAt(Instant.now())
                .build();

        List<LedgerEntry> entries = List.of(
            buildEntry(tx, receivable, EntryType.DEBIT,  amountPaise, currency,
                       "Razorpay receivable — payment " + paymentId),
            buildEntry(tx, revenue,    EntryType.CREDIT, amountPaise, currency,
                       "Revenue recognized — payment " + paymentId)
        );

        validateBalance(entries);
        tx.getEntries().addAll(entries);
        txRepo.save(tx);

        log.info("Ledger: posted payment captured: paymentId={}, amount={}p", paymentId, amountPaise);
    }

    /**
     * Post ledger entries for a refund.
     * Idempotent: if already posted for this refundId, does nothing.
     */
    @Transactional
    public void postRefund(UUID refundId, long amountPaise, String currency) {
        String idempotencyKey = "REFUND-" + refundId;
        if (txRepo.existsByIdempotencyKey(idempotencyKey)) {
            log.info("Ledger: refund already posted, skipping: {}", refundId);
            return;
        }

        LedgerAccount refundsPayable = getAccount("REFUNDS_PAYABLE");
        LedgerAccount receivable     = getAccount("RAZORPAY_RECEIVABLE");

        LedgerTransaction tx = LedgerTransaction.builder()
                .sourceType("REFUND")
                .sourceId(refundId)
                .description("Refund issued: " + refundId)
                .idempotencyKey(idempotencyKey)
                .amountPaise(amountPaise)
                .currency(currency)
                .postedAt(Instant.now())
                .build();

        List<LedgerEntry> entries = List.of(
            buildEntry(tx, refundsPayable, EntryType.DEBIT,  amountPaise, currency,
                       "Refund payable — " + refundId),
            buildEntry(tx, receivable,     EntryType.CREDIT, amountPaise, currency,
                       "Reduces Razorpay receivable — refund " + refundId)
        );

        validateBalance(entries);
        tx.getEntries().addAll(entries);
        txRepo.save(tx);

        log.info("Ledger: posted refund: refundId={}, amount={}p", refundId, amountPaise);
    }

    /**
     * Post ledger entries for a Razorpay settlement.
     * When Razorpay transfers net amount to merchant bank after deducting fees.
     */
    @Transactional
    public void postSettlement(UUID settlementId, long grossPaise, long feesPaise, String currency) {
        String idempotencyKey = "SETTLEMENT-" + settlementId;
        if (txRepo.existsByIdempotencyKey(idempotencyKey)) {
            log.info("Ledger: settlement already posted, skipping: {}", settlementId);
            return;
        }

        long netPaise = grossPaise - feesPaise;

        LedgerAccount merchantBank  = getAccount("MERCHANT_BANK");
        LedgerAccount fees          = getAccount("RAZORPAY_FEES");
        LedgerAccount receivable    = getAccount("RAZORPAY_RECEIVABLE");

        LedgerTransaction tx = LedgerTransaction.builder()
                .sourceType("SETTLEMENT")
                .sourceId(settlementId)
                .description("Razorpay settlement: " + settlementId)
                .idempotencyKey(idempotencyKey)
                .amountPaise(grossPaise)
                .currency(currency)
                .postedAt(Instant.now())
                .build();

        // DR Merchant Bank (net received)
        // DR Razorpay Fees (fees paid)
        // CR Razorpay Receivable (gross settled)
        List<LedgerEntry> entries = List.of(
            buildEntry(tx, merchantBank, EntryType.DEBIT,  netPaise,   currency,
                       "Net settlement received in bank"),
            buildEntry(tx, fees,         EntryType.DEBIT,  feesPaise,  currency,
                       "Razorpay gateway fees deducted"),
            buildEntry(tx, receivable,   EntryType.CREDIT, grossPaise, currency,
                       "Settlement clears receivable")
        );

        validateBalance(entries);
        tx.getEntries().addAll(entries);
        txRepo.save(tx);

        log.info("Ledger: posted settlement: id={}, gross={}p, fees={}p, net={}p",
                settlementId, grossPaise, feesPaise, netPaise);
    }

    @Transactional(readOnly = true)
    public List<LedgerAccount> listAccounts() {
        return accountRepo.findAll();
    }

    @Transactional(readOnly = true)
    public org.springframework.data.domain.Page<LedgerTransaction> listTransactions(org.springframework.data.domain.Pageable pageable) {
        return txRepo.findAllByOrderByPostedAtDesc(pageable);
    }

    @Transactional(readOnly = true)
    public org.springframework.data.domain.Page<LedgerTransaction> listTransactionsBySourceType(String sourceType, org.springframework.data.domain.Pageable pageable) {
        return txRepo.findBySourceTypeOrderByPostedAtDesc(sourceType, pageable);
    }

    // ── Private helpers ────────────────────────────────────────────────────────

    private LedgerAccount getAccount(String code) {
        return accountRepo.findByCode(code)
                .orElseThrow(() -> new IllegalStateException(
                        "Ledger account not found: " + code +
                        ". Ensure V4 migration ran successfully."));
    }

    private LedgerEntry buildEntry(LedgerTransaction tx, LedgerAccount account,
                                    EntryType type, long amountPaise,
                                    String currency, String description) {
        return LedgerEntry.builder()
                .ledgerTransaction(tx)
                .ledgerAccount(account)
                .entryType(type)
                .amountPaise(amountPaise)
                .currency(currency)
                .description(description)
                .build();
    }

    /**
     * Validates that total debits == total credits.
     * This is the fundamental double-entry invariant.
     *
     * @throws IllegalArgumentException if the entries don't balance
     */
    private void validateBalance(List<LedgerEntry> entries) {
        long totalDebits  = entries.stream()
                .filter(e -> e.getEntryType() == EntryType.DEBIT)
                .mapToLong(LedgerEntry::getAmountPaise)
                .sum();
        long totalCredits = entries.stream()
                .filter(e -> e.getEntryType() == EntryType.CREDIT)
                .mapToLong(LedgerEntry::getAmountPaise)
                .sum();

        if (totalDebits != totalCredits) {
            throw new IllegalArgumentException(
                    "Ledger entries don't balance: debits=" + totalDebits
                    + " != credits=" + totalCredits);
        }
    }
}
