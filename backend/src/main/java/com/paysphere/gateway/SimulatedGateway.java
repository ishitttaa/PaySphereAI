package com.paysphere.gateway;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

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
                              String status, String method, boolean international) {}

    private static final String ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789";

    private final Map<String, SimOrder> orders = new ConcurrentHashMap<>();
    private final Map<String, SimPayment> payments = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Object>> refundsByKey = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();
    private final byte[] paymentSecret = new byte[32];
    private final byte[] webhookSecret = new byte[32];

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
            // Looks like a real Razorpay 4xx rejection, so RefundService treats it as definitive.
            throw new RuntimeException("Failed to create refund",
                    new IOException("Razorpay API error: HTTP 400 -> refund exceeds payment amount"));
        }
        // Same idempotency key returns the same refund, like the real API.
        return refundsByKey.computeIfAbsent(idempotencyKey, k -> {
            Map<String, Object> result = new HashMap<>();
            result.put("id", "rfnd_sim_" + randomId(14));
            result.put("status", "processed");
            result.put("amount", amountPaise);
            result.put("payment_id", paymentId);
            return result;
        });
    }

    // ── Simulator-only: stands in for the customer paying in the checkout popup ──

    public Optional<Map<String, Object>> payOrder(String orderId, boolean success,
                                                  String method, boolean international) {
        SimOrder order = orders.get(orderId);
        if (order == null) {
            return Optional.empty();
        }
        String paymentId = "pay_sim_" + randomId(14);
        payments.put(paymentId, new SimPayment(paymentId, orderId, order.amountPaise(),
                order.currency(), success ? "captured" : "failed", method, international));

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