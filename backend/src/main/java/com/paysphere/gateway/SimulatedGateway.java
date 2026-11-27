package com.paysphere.gateway;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import java.util.Comparator;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.List;
/**
 * A fake payment gateway for local development and demos. No real money, no network.
 * Everything AFTER the gateway (signature checks, ledger, audit, risk, refunds) is real code.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "payment.gateway", havingValue = "simulator")
public class SimulatedGateway implements PaymentGateway {

    private record SimOrder(long amountPaise, String currency) {}
    private record SimPayment(String id, String orderId, long amountPaise, String currency,
                              String status, String method, boolean international, long createdAt) {}
    private static final String ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789";

    private final Map<String, SimOrder> orders = new ConcurrentHashMap<>();
    private final Map<String, SimPayment> payments = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Object>> refundsByKey = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();
    private final byte[] paymentSecret = new byte[32];
    private final byte[] webhookSecret = new byte[32];
    
    public enum RefundMode { NORMAL, TIMEOUT_AFTER_SUCCESS, TIMEOUT_NO_EFFECT }

    private volatile RefundMode refundMode = RefundMode.NORMAL;

    /** Simulator-only: makes refund calls misbehave, so reconciliation can be tried out. */
    public void setRefundMode(RefundMode mode) {
        this.refundMode = mode;
        log.warn("PAYMENT SIMULATOR: refund mode is now {}", mode);
    }

    private GatewayException simulatedTimeout() {
        return new GatewayException(GatewayException.Outcome.UNKNOWN, null, "Simulated gateway timeout",
                new java.net.http.HttpTimeoutException("simulated timeout"));
    }
    public SimulatedGateway(@Value("${APP_ENV:development}") String appEnv) {
        if ("production".equalsIgnoreCase(appEnv)) {
            throw new IllegalStateException(
                    "FATAL: the payment simulator must never run when APP_ENV=production");
        }
        random.nextBytes(paymentSecret);
        random.nextBytes(webhookSecret);
        log.warn("PAYMENT SIMULATOR ACTIVE: no real money and no real Razorpay calls");
    }

    @Override
    public Map<String, Object> createOrder(long amountPaise, String currency,
                                           String receipt, String idempotencyKey) {
        String id = "order_sim_" + randomId(14);
        orders.put(id, new SimOrder(amountPaise, currency));
        Map<String, Object> result = new HashMap<>();
        result.put("id", id);
        result.put("status", "created");
        result.put("amount", amountPaise);
        result.put("currency", currency);
        result.put("receipt", receipt);
        return result;
    }

    @Override
    public boolean verifyPaymentSignature(String orderId, String paymentId, String signature) {
        return matches(hmacHex(paymentSecret, orderId + "|" + paymentId), signature);
    }

    @Override
    public boolean verifyWebhookSignature(String payload, String signature) {
        return matches(hmacHex(webhookSecret, payload), signature);
    }

    @Override
    public Map<String, Object> fetchPayment(String paymentId) {
        SimPayment p = payments.get(paymentId);
        if (p == null) {
            throw new IllegalArgumentException("Unknown simulated payment: " + paymentId);
        }
        Map<String, Object> result = new HashMap<>();
        result.put("id", p.id());
        result.put("status", p.status());
        result.put("amount", p.amountPaise());
        result.put("currency", p.currency());
        result.put("order_id", p.orderId());
        result.put("method", p.method());
        result.put("captured", "captured".equals(p.status()));
        result.put("international", p.international());
        return result;
    }

    @Override
    public Map<String, Object> createRefund(String paymentId, long amountPaise,
                                            String reason, String idempotencyKey) {
        SimPayment p = payments.get(paymentId);
        if (p != null && amountPaise > p.amountPaise()) {
            // A clear rejection, like the real gateway's HTTP 400: nothing was refunded.
            throw GatewayException.fromHttpStatus(400, "refund exceeds payment amount");
        }
        if (refundMode == RefundMode.TIMEOUT_NO_EFFECT && !refundsByKey.containsKey(idempotencyKey)) {
            throw simulatedTimeout();      // the request "never arrived"
        }
        // Same idempotency key returns the same refund, like the real API.
        Map<String, Object> refund = refundsByKey.computeIfAbsent(idempotencyKey, k -> {
            Map<String, Object> result = new HashMap<>();
            result.put("id", "rfnd_sim_" + randomId(14));
            result.put("status", "processed");
            result.put("amount", amountPaise);
            result.put("currency", p == null ? "INR" : p.currency());
            result.put("payment_id", paymentId);
            result.put("created_at", java.time.Instant.now().getEpochSecond());
            return result;
        });
        if (refundMode == RefundMode.TIMEOUT_AFTER_SUCCESS) {
            throw simulatedTimeout();      // the refund happened, but we never heard back
        }
        return refund;
    }

    @Override
    public List<Map<String, Object>> fetchRefundsForPayment(String razorpayPaymentId) {
        if (!payments.containsKey(razorpayPaymentId)) {
            // Like the real gateway: it does not know this payment id.
            throw GatewayException.fromHttpStatus(400, "The id provided does not exist");
        }
        return refundsByKey.values().stream()
                .filter(r -> razorpayPaymentId.equals(r.get("payment_id")))
                .sorted((a, b) -> Long.compare(((Number) b.get("created_at")).longValue(),
                                               ((Number) a.get("created_at")).longValue()))
                .toList();
    }
        @Override
    public List<Map<String, Object>> fetchPaymentsForOrder(String razorpayOrderId) {
        if (!orders.containsKey(razorpayOrderId)) {
            throw GatewayException.fromHttpStatus(400, "The id provided does not exist");
        }
        return payments.values().stream()
                .filter(p -> razorpayOrderId.equals(p.orderId()))
                .sorted(Comparator.comparingLong(SimPayment::createdAt))
                .map(p -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("id", p.id());
                    m.put("order_id", p.orderId());
                    m.put("amount", p.amountPaise());
                    m.put("currency", p.currency());
                    m.put("status", p.status());
                    m.put("captured", "captured".equals(p.status()));
                    m.put("method", p.method());
                    m.put("international", p.international());
                    m.put("amount_refunded", refundedAmount(p.id()));
                    m.put("created_at", p.createdAt());
                    return m;
                })
                .toList();
    }

    private long refundedAmount(String paymentId) {
        return refundsByKey.values().stream()
                .filter(r -> paymentId.equals(r.get("payment_id")))
                .mapToLong(r -> ((Number) r.get("amount")).longValue())
                .sum();
    }
    // ── Simulator-only: stands in for the customer paying in the checkout popup ──

    public Optional<Map<String, Object>> payOrder(String orderId, boolean success,
                                                  String method, boolean international) {
        return payOrder(orderId, success, method, international, 0);
    }

    /** minutesAgo backdates the payment, so reconciliation (which ignores very recent payments) can be tried. */
    public Optional<Map<String, Object>> payOrder(String orderId, boolean success, String method,
                                                  boolean international, int minutesAgo) {
        SimOrder order = orders.get(orderId);
        if (order == null) {
            return Optional.empty();
        }
        String paymentId = "pay_sim_" + randomId(14);
        long createdAt = java.time.Instant.now().getEpochSecond();
         payments.put(paymentId, new SimPayment(paymentId, orderId, order.amountPaise(),
                order.currency(), success ? "captured" : "failed", method, international,
                java.time.Instant.now().getEpochSecond() - minutesAgo * 60L));

        Map<String, Object> result = new HashMap<>();
        result.put("razorpayPaymentId", paymentId);
        result.put("razorpayOrderId", orderId);
        if (success) {
            result.put("outcome", "SUCCESS");
            result.put("razorpaySignature", hmacHex(paymentSecret, orderId + "|" + paymentId));
        } else {
            result.put("outcome", "FAILURE");
            result.put("errorDescription", "Simulated payment failure");
        }
        return Optional.of(result);
    }
    /** Simulator-only: signs a webhook body the way the real gateway would. */
    public String signWebhook(String payload) {
        return hmacHex(webhookSecret, payload);
    }
    // ── helpers ──

    private String randomId(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }

    private static String hmacHex(byte[] key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC failed", e);
        }
    }

    private static boolean matches(String expected, String actual) {
        return actual != null && MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
    }
}