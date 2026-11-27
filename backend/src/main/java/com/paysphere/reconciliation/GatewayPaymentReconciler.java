package com.paysphere.reconciliation;

import com.paysphere.gateway.PaymentGateway;
import com.paysphere.payment.entity.PaymentStatus;
import com.paysphere.payment.repository.PaymentRepository;
import com.paysphere.payment.service.PaymentService;
import com.paysphere.reconciliation.ReconciliationQueries.RecentPayment;
import com.paysphere.reconciliation.ReconciliationQueries.UnpaidOrder;
import com.paysphere.risk.entity.RiskSeverity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Compares the gateway's payments with ours.
 *  1. Orders with no payment on our side: ask the gateway. A payment it captured, that we never
 *     recorded (browser closed, webhook missed), is recorded now through the same code as the webhook.
 *  2. Payments we hold as captured: check the gateway agrees on status, amount and currency.
 *
 * Not transactional on purpose: each repair runs in its own transaction, and one gateway error
 * must not stop the rest.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GatewayPaymentReconciler {

    public static final Duration ORDER_LOOKBACK = Duration.ofHours(48);
    public static final Duration PAYMENT_LOOKBACK = Duration.ofHours(24);
    /** Payments younger than this may still be completing normally (the customer's verify call). */
    public static final Duration GRACE = Duration.ofMinutes(10);
    public static final int MAX_PER_RUN = 200;

    private final ReconciliationQueries queries;
    private final PaymentGateway paymentGateway;
    private final PaymentService paymentService;
    private final PaymentRepository paymentRepository;

    public GatewayPaymentResult reconcile(Instant now) {
        Map<String, Integer> outcomes = new TreeMap<>();
        List<ReconciliationFinding> findings = new ArrayList<>();
        recordMissedPayments(now, outcomes, findings);
        compareRecordedPayments(now, outcomes, findings);
        if (!outcomes.isEmpty()) {
            log.info("Gateway payment reconciliation: {}", outcomes);
        }
        return new GatewayPaymentResult(outcomes, findings);
    }

    private void recordMissedPayments(Instant now, Map<String, Integer> outcomes,
                                      List<ReconciliationFinding> findings) {
        for (UnpaidOrder order : queries.findRecentOrdersWithoutPayment(now.minus(ORDER_LOOKBACK), MAX_PER_RUN)) {
            List<Map<String, Object>> gatewayPayments;
            try {
                gatewayPayments = paymentGateway.fetchPaymentsForOrder(order.razorpayOrderId());
            } catch (RuntimeException e) {
                log.error("Could not ask the gateway about order {}: {}", order.orderReference(), e.getMessage());
                outcomes.merge("ORDER_LOOKUP_ERROR", 1, Integer::sum);
                continue;
            }

            for (Map<String, Object> gp : gatewayPayments) {
                String paymentId = gp.get("id") instanceof String s ? s : null;
                if (paymentId == null || !Boolean.TRUE.equals(gp.get("captured")) || !oldEnough(gp, now)) {
                    continue;
                }
                if (paymentRepository.existsByRazorpayPaymentIdAndStatusNot(paymentId, PaymentStatus.FAILED)) {
                    continue;
                }
                if (refundedAtGateway(gp) > 0) {
                    findings.add(new ReconciliationFinding("RECON_GATEWAY_PAYMENT_NOT_RECORDED",
                            "BusinessOrder", order.id(), RiskSeverity.CRITICAL,
                            "Gateway payment " + paymentId + " was captured and already (partly) refunded, "
                                    + "but we never recorded it: review by hand"));
                    outcomes.merge("NEEDS_REVIEW", 1, Integer::sum);
                    continue;
                }
                try {
                    paymentService.recordGatewayCapture(paymentId, order.razorpayOrderId(), gp, "RECONCILIATION");
                    outcomes.merge("RECORDED", 1, Integer::sum);
                    log.warn("Recorded payment {} that the gateway had captured for order {}",
                            paymentId, order.orderReference());
                } catch (RuntimeException e) {
                    findings.add(new ReconciliationFinding("RECON_GATEWAY_PAYMENT_NOT_RECORDED",
                            "BusinessOrder", order.id(), RiskSeverity.CRITICAL,
                            "Gateway payment " + paymentId + " was captured but could not be recorded: "
                                    + e.getMessage()));
                    outcomes.merge("RECORD_FAILED", 1, Integer::sum);
                }
            }
        }
    }

    private void compareRecordedPayments(Instant now, Map<String, Integer> outcomes,
                                         List<ReconciliationFinding> findings) {
        for (RecentPayment ours : queries.findRecentCapturedPayments(now.minus(PAYMENT_LOOKBACK), MAX_PER_RUN)) {
            Map<String, Object> gateway;
            try {
                gateway = paymentGateway.fetchPayment(ours.razorpayPaymentId());
            } catch (RuntimeException e) {
                outcomes.merge("PAYMENT_LOOKUP_ERROR", 1, Integer::sum);
                continue;
            }
            String problem = describeMismatch(ours, gateway);
            if (problem == null) {
                outcomes.merge("PAYMENTS_MATCHED", 1, Integer::sum);
            } else {
                findings.add(new ReconciliationFinding("RECON_GATEWAY_PAYMENT_MISMATCH", "Payment",
                        ours.id(), RiskSeverity.CRITICAL, problem));
            }
        }
    }

    private static String describeMismatch(RecentPayment ours, Map<String, Object> gateway) {
        if (!Boolean.TRUE.equals(gateway.get("captured"))) {
            return "We hold this payment as captured, but the gateway says its status is "
                    + gateway.get("status");
        }
        if (gateway.get("amount") instanceof Number n && n.longValue() != ours.amountPaise()) {
            return "We hold " + ours.amountPaise() + "p but the gateway captured " + n.longValue() + "p";
        }
        if (gateway.get("currency") instanceof String c && !c.equals(ours.currency())) {
            return "We hold currency " + ours.currency() + " but the gateway says " + c;
        }
        return null;
    }

    private static boolean oldEnough(Map<String, Object> gatewayPayment, Instant now) {
        return gatewayPayment.get("created_at") instanceof Number created
                && Instant.ofEpochSecond(created.longValue()).isBefore(now.minus(GRACE));
    }

    private static long refundedAtGateway(Map<String, Object> gatewayPayment) {
        return gatewayPayment.get("amount_refunded") instanceof Number n ? n.longValue() : 0L;
    }
}