package com.paysphere.reconciliation;

import com.paysphere.risk.entity.RiskSeverity;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Read-only consistency checks between payments, refunds and the ledger.
 * Every query returns the rows that are WRONG; an empty result means the check passed.
 */
@Repository
@RequiredArgsConstructor
public class ReconciliationQueries {

    private final JdbcTemplate jdbc;

    public List<ReconciliationFinding> findAllProblems(Instant stalePendingRefundsCreatedBefore) {
        List<ReconciliationFinding> out = new ArrayList<>();

        // 1. A captured (or fully refunded) payment must have been posted to the ledger.
        out.addAll(jdbc.query("""
                SELECT p.id FROM payments p
                WHERE p.status IN ('CAPTURED', 'REFUNDED')
                  AND NOT EXISTS (SELECT 1 FROM ledger_transactions t
                                  WHERE t.idempotency_key = 'PAYMENT-' || p.id::text)
                """,
                (rs, i) -> new ReconciliationFinding("RECON_PAYMENT_NOT_IN_LEDGER", "Payment",
                        rs.getObject("id", UUID.class), RiskSeverity.CRITICAL,
                        "Payment is captured but has no ledger posting")));

        // 2. The ledger amount must equal the payment amount.
        out.addAll(jdbc.query("""
                SELECT p.id, p.amount_paise AS expected, t.amount_paise AS actual
                FROM payments p
                JOIN ledger_transactions t ON t.idempotency_key = 'PAYMENT-' || p.id::text
                WHERE t.amount_paise <> p.amount_paise
                """,
                (rs, i) -> new ReconciliationFinding("RECON_PAYMENT_LEDGER_AMOUNT_MISMATCH", "Payment",
                        rs.getObject("id", UUID.class), RiskSeverity.CRITICAL,
                        "Ledger has " + rs.getLong("actual") + "p but the payment is "
                                + rs.getLong("expected") + "p")));

        // 3. A processed refund must have been posted to the ledger.
        out.addAll(jdbc.query("""
                SELECT r.id FROM refunds r
                WHERE r.status = 'PROCESSED'
                  AND NOT EXISTS (SELECT 1 FROM ledger_transactions t
                                  WHERE t.idempotency_key = 'REFUND-' || r.id::text)
                """,
                (rs, i) -> new ReconciliationFinding("RECON_REFUND_NOT_IN_LEDGER", "Refund",
                        rs.getObject("id", UUID.class), RiskSeverity.CRITICAL,
                        "Refund is processed but has no ledger posting")));

        // 4. A refund that was posted and later FAILED must have a reversing entry.
        out.addAll(jdbc.query("""
                SELECT r.id FROM refunds r
                WHERE r.status = 'FAILED'
                  AND EXISTS (SELECT 1 FROM ledger_transactions t
                              WHERE t.idempotency_key = 'REFUND-' || r.id::text)
                  AND NOT EXISTS (SELECT 1 FROM ledger_transactions t
                                  WHERE t.idempotency_key = 'REFUND-REVERSAL-' || r.id::text)
                """,
                (rs, i) -> new ReconciliationFinding("RECON_FAILED_REFUND_NOT_REVERSED", "Refund",
                        rs.getObject("id", UUID.class), RiskSeverity.CRITICAL,
                        "Refund failed after it was posted, but the ledger was never reversed")));

        // 5. Every ledger transaction must balance: debits equal credits.
        out.addAll(jdbc.query("""
                SELECT ledger_transaction_id AS id FROM ledger_entries
                GROUP BY ledger_transaction_id
                HAVING SUM(CASE WHEN entry_type = 'DEBIT'  THEN amount_paise ELSE 0 END)
                    <> SUM(CASE WHEN entry_type = 'CREDIT' THEN amount_paise ELSE 0 END)
                """,
                (rs, i) -> new ReconciliationFinding("RECON_UNBALANCED_LEDGER_TRANSACTION",
                        "LedgerTransaction", rs.getObject("id", UUID.class), RiskSeverity.CRITICAL,
                        "Ledger transaction debits do not equal credits")));

        // 6. The refunded total on a payment must equal the sum of its processed refunds.
        out.addAll(jdbc.query("""
                SELECT p.id, p.amount_refunded_paise AS recorded,
                       COALESCE(SUM(r.amount_paise) FILTER (WHERE r.status = 'PROCESSED'), 0) AS expected
                FROM payments p
                LEFT JOIN refunds r ON r.payment_id = p.id
                GROUP BY p.id, p.amount_refunded_paise
                HAVING p.amount_refunded_paise
                    <> COALESCE(SUM(r.amount_paise) FILTER (WHERE r.status = 'PROCESSED'), 0)
                """,
                (rs, i) -> new ReconciliationFinding("RECON_REFUND_TOTAL_MISMATCH", "Payment",
                        rs.getObject("id", UUID.class), RiskSeverity.HIGH,
                        "Payment says " + rs.getLong("recorded") + "p refunded but its processed refunds add up to "
                                + rs.getLong("expected") + "p")));

        // 7. A refund should not stay PENDING for long.
        out.addAll(jdbc.query("""
                SELECT r.id FROM refunds r
                WHERE r.status = 'PENDING' AND r.created_at < ?
                """,
                (rs, i) -> new ReconciliationFinding("RECON_STALE_PENDING_REFUND", "Refund",
                        rs.getObject("id", UUID.class), RiskSeverity.HIGH,
                        "Refund has been PENDING for too long: confirm its outcome with the gateway"),
                Timestamp.from(stalePendingRefundsCreatedBefore)));

        return out;
    }
        public record UnpaidOrder(UUID id, String razorpayOrderId, String orderReference) {}

    public record RecentPayment(UUID id, String razorpayPaymentId, long amountPaise, String currency) {}

    /** Recent orders that carry a gateway order id but have no payment row on our side. */
    public List<UnpaidOrder> findRecentOrdersWithoutPayment(Instant createdAfter, int limit) {
        return jdbc.query("""
                SELECT o.id, o.razorpay_order_id, o.order_reference FROM business_orders o
                WHERE o.status IN ('PAYMENT_INITIATED', 'PAYMENT_EXPIRED', 'PAYMENT_FAILED')
                  AND o.razorpay_order_id IS NOT NULL
                  AND o.created_at > ?
                  AND NOT EXISTS (SELECT 1 FROM payments p WHERE p.business_order_id = o.id)
                ORDER BY o.created_at DESC
                LIMIT ?
                """,
                (rs, i) -> new UnpaidOrder(rs.getObject("id", UUID.class),
                        rs.getString("razorpay_order_id"), rs.getString("order_reference")),
                Timestamp.from(createdAfter), limit);
    }

    /** Recent payments we hold as captured (or fully refunded). */
    public List<RecentPayment> findRecentCapturedPayments(Instant createdAfter, int limit) {
        return jdbc.query("""
                SELECT id, razorpay_payment_id, amount_paise, currency FROM payments
                WHERE status IN ('CAPTURED', 'REFUNDED') AND created_at > ?
                ORDER BY created_at DESC
                LIMIT ?
                """,
                (rs, i) -> new RecentPayment(rs.getObject("id", UUID.class),
                        rs.getString("razorpay_payment_id"), rs.getLong("amount_paise"),
                        rs.getString("currency")),
                Timestamp.from(createdAfter), limit);
    }
}