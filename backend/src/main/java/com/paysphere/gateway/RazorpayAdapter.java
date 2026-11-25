package com.paysphere.gateway;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import com.paysphere.common.exception.SignatureVerificationException;
import com.paysphere.config.RazorpayConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONObject;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * RazorpayAdapter — Implements PaymentGateway using the Razorpay REST API.
 *
 * WHY manual HTTP calls instead of the Razorpay Java SDK?
 * The SDK abstracts away what's happening on the wire. By understanding
 * the raw API, you can:
 * 1. Debug failures by reading actual HTTP responses
 * 2. Understand exactly what signature verification checks
 * 3. Handle idempotency keys correctly (SDK does this opaquely)
 *
 * In production, using the official SDK is fine. For learning, raw HTTP is better.
 *
 * SECURITY: This class handles keySecret — it is NEVER logged, returned in
 * responses, or passed to any method that might serialize it.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "payment.gateway", havingValue = "razorpay", matchIfMissing = true)
public class RazorpayAdapter implements PaymentGateway {

    private final RazorpayConfig config;

    /**
     * Creates an order in Razorpay by calling POST /v1/orders.
     *
     * Razorpay order = permission for customer to pay a specific amount.
     * Our local business_order is the source of truth for the amount.
     *
     * Idempotency: Razorpay supports X-Idempotency-Key header.
     * If this call fails and is retried with the same key, Razorpay
     * returns the existing order instead of creating a duplicate.
     */
    @Override
    public Map<String, Object> createOrder(long amountPaise, String currency,
                                            String receipt, String idempotencyKey) {
        log.info("Creating Razorpay order: amount={}p, currency={}, receipt={}",
                amountPaise, currency, receipt);

        try {
            // Build the JSON request body
            JSONObject requestBody = new JSONObject();
            requestBody.put("amount", amountPaise);
            requestBody.put("currency", currency);
            requestBody.put("receipt", receipt);

            // Make the HTTP call to Razorpay
            String responseBody = RazorpayHttpClient.post(
                    config.getBaseUrl() + "/orders",
                    requestBody.toString(),
                    config.getKeyId(),
                    config.getKeySecret(),
                    idempotencyKey
            );

            JSONObject jsonResponse = new JSONObject(responseBody);
            Map<String, Object> result = new HashMap<>();
            result.put("id", jsonResponse.getString("id"));
            result.put("status", jsonResponse.getString("status"));
            result.put("amount", jsonResponse.getLong("amount"));
            result.put("currency", jsonResponse.getString("currency"));
            result.put("receipt", jsonResponse.optString("receipt", receipt));

            log.info("Razorpay order created: orderId={}, status={}",
                    result.get("id"), result.get("status"));

            return result;

        } catch (Exception e) {
            log.error("Failed to create Razorpay order for receipt={}: {}", receipt, e.getMessage());
            throw new RuntimeException("Failed to create payment order: " + e.getMessage(), e);
        }
    }

    /**
     * Verifies the HMAC-SHA256 signature from Razorpay checkout.
     *
     * Razorpay signature verification algorithm:
     *   message = razorpay_order_id + "|" + razorpay_payment_id
     *   expected_signature = HMAC-SHA256(message, key_secret)
     *   verify: expected_signature == provided_signature (constant-time comparison!)
     *
     * WHY constant-time comparison?
     * String.equals() short-circuits on first mismatch → timing attack possible.
     * MessageDigest.isEqual() always checks all bytes → timing safe.
     *
     * SECURITY: NEVER log the signature or key_secret.
     */
    @Override
    public boolean verifyPaymentSignature(String razorpayOrderId,
                                          String razorpayPaymentId,
                                          String signature) {
        try {
            String message = razorpayOrderId + "|" + razorpayPaymentId;
            String expectedSignature = computeHmacSha256(message, config.getKeySecret());
            boolean valid = java.security.MessageDigest.isEqual(
                    expectedSignature.getBytes(StandardCharsets.UTF_8),
                    signature.getBytes(StandardCharsets.UTF_8)
            );
            if (!valid) {
                log.warn("Payment signature mismatch for orderId={}", razorpayOrderId);
            }
            return valid;
        } catch (Exception e) {
            log.error("Signature verification error for orderId={}: {}", razorpayOrderId, e.getMessage());
            return false;
        }
    }

    /**
     * Verifies the HMAC-SHA256 signature of a Razorpay webhook.
     *
     * Webhook signature algorithm:
     *   expected = HMAC-SHA256(rawPayload, webhook_secret)
     *   verify: expected == X-Razorpay-Signature header (constant-time)
     *
     * SECURITY: webhook_secret is DIFFERENT from key_secret.
     * Set it in Razorpay Dashboard → Settings → Webhooks → Secret.
     */
    @Override
    public boolean verifyWebhookSignature(String payload, String signature) {
        try {
            String expectedSignature = computeHmacSha256(payload, config.getWebhookSecret());
            return java.security.MessageDigest.isEqual(
                    expectedSignature.getBytes(StandardCharsets.UTF_8),
                    signature.getBytes(StandardCharsets.UTF_8)
            );
        } catch (Exception e) {
            log.error("Webhook signature verification error: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Fetches full payment details from Razorpay.
     * Used after webhook delivery or as a fallback to confirm payment status.
     */
    @Override
    public Map<String, Object> fetchPayment(String razorpayPaymentId) {
        log.info("Fetching Razorpay payment: paymentId={}", razorpayPaymentId);
        try {
            String responseBody = RazorpayHttpClient.get(
                    config.getBaseUrl() + "/payments/" + razorpayPaymentId,
                    config.getKeyId(),
                    config.getKeySecret()
            );
            JSONObject json = new JSONObject(responseBody);
            Map<String, Object> result = new HashMap<>();
            result.put("id", json.getString("id"));
            result.put("status", json.getString("status"));
            result.put("amount", json.getLong("amount"));
            result.put("currency", json.getString("currency"));
            result.put("order_id", json.optString("order_id", ""));
            result.put("method", json.optString("method", ""));
            result.put("captured", json.optBoolean("captured", false));
            return result;
        } catch (Exception e) {
            log.error("Failed to fetch Razorpay payment {}: {}", razorpayPaymentId, e.getMessage());
            throw new RuntimeException("Failed to fetch payment details: " + e.getMessage(), e);
        }
    }

    /**
     * Creates a refund for a captured payment.
     *
     * Partial refunds are supported: amountPaise can be less than the original.
     * Idempotency key prevents duplicate refunds if the API call is retried.
     */
    @Override
    public Map<String, Object> createRefund(String razorpayPaymentId,
                                             long amountPaise,
                                             String reason,
                                             String idempotencyKey) {
        log.info("Creating Razorpay refund: paymentId={}, amount={}p", razorpayPaymentId, amountPaise);
        try {
            JSONObject requestBody = new JSONObject();
            requestBody.put("amount", amountPaise);
            if (reason != null && !reason.isBlank()) {
                requestBody.put("notes", new JSONObject().put("reason", reason));
            }
            String responseBody = RazorpayHttpClient.post(
                    config.getBaseUrl() + "/payments/" + razorpayPaymentId + "/refund",
                    requestBody.toString(),
                    config.getKeyId(),
                    config.getKeySecret(),
                    idempotencyKey
            );
            JSONObject json = new JSONObject(responseBody);
            Map<String, Object> result = new HashMap<>();
            result.put("id", json.getString("id"));
            result.put("status", json.optString("status", "pending"));
            result.put("amount", json.getLong("amount"));
            result.put("payment_id", json.getString("payment_id"));
            log.info("Refund created: refundId={}, status={}", result.get("id"), result.get("status"));
            return result;
        } catch (GatewayException e) {
            log.error("Gateway answered the refund for payment {} with: {}", razorpayPaymentId, e.getMessage());
            throw e;
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.error("Failed to create refund for payment {}: {}", razorpayPaymentId, e.getMessage());
            // Timeout, network error or unreadable reply: we do NOT know whether the refund happened.
            throw new GatewayException(GatewayException.Outcome.UNKNOWN, null,
                    "Failed to create refund: " + e.getMessage(), e);
        }
    }

    // ── HMAC-SHA256 Helper ──────────────────────────────────────────────────

    /**
     * Computes HMAC-SHA256 of a message using the provided secret key.
     * Returns lowercase hex string — matches Razorpay's expected format.
     *
     * SECURITY: The key (secret) is NEVER logged. The result (signature) should
     * be treated as sensitive and never logged either.
     */
    private String computeHmacSha256(String message, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        SecretKeySpec keySpec = new SecretKeySpec(
                secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        mac.init(keySpec);
        byte[] hash = mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
        return bytesToHex(hash);
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
