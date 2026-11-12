package com.paysphere.webhook.service;

import com.paysphere.gateway.PaymentGateway;
import com.paysphere.payment.service.PaymentService;
import com.paysphere.webhook.entity.WebhookEvent;
import com.paysphere.webhook.entity.WebhookStatus;
import com.paysphere.webhook.repository.WebhookEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONObject;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * WebhookService — Processes incoming Razorpay webhook events.
 *
 * M5 implementation:
 *
 * 1. FIRST: Verify the X-Razorpay-Signature header using HMAC-SHA256.
 *    WHY: Anyone can POST to your webhook endpoint. Only Razorpay knows
 *    the webhook_secret and can produce the correct signature.
 *    If we skip this, an attacker could fake a payment.captured event.
 *
 * 2. Check for duplicate event ID (idempotency).
 *    Razorpay may deliver the same event multiple times (at-least-once delivery).
 *
 * 3. Store the raw payload for audit trail.
 *
 * 4. Process the event based on event type:
 *    - payment.captured → update payment to CAPTURED
 *    - payment.failed → update order to PAYMENT_FAILED
 *    - refund.created → record refund
 *    - (others → log and skip)
 *
 * 5. Mark the event as PROCESSED or FAILED.
 *
 * WHY store-then-process?
 * We store the event immediately so it's in our audit log even if processing fails.
 * A FAILED event can be reprocessed. A SKIPPED event means idempotency triggered.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WebhookService {

    private final WebhookEventRepository webhookEventRepository;
    private final PaymentGateway paymentGateway;
    private final PaymentService paymentService;

    /**
     * Main entry point: receive and process a Razorpay webhook.
     *
     * @param rawPayload   The raw request body (bytes → String, not parsed JSON)
     * @param signature    The X-Razorpay-Signature header value
     */
    @Transactional
    public void processWebhook(String rawPayload, String signature) {

        // Step 1: Verify signature BEFORE parsing the payload
        // WHY raw payload? Razorpay signs the raw bytes — any parsing could alter whitespace.
        boolean signatureValid = paymentGateway.verifyWebhookSignature(rawPayload, signature);
        if (!signatureValid) {
            log.warn("SECURITY: Webhook received with invalid signature. Rejecting.");
            throw new SecurityException("Webhook signature verification failed");
        }

        // Step 2: Parse the payload
        JSONObject payload = new JSONObject(rawPayload);
        String eventId = payload.optString("id", "unknown-" + System.currentTimeMillis());
        String eventType = payload.optString("event", "unknown");

        log.info("Webhook received: eventId={}, eventType={}", eventId, eventType);

        // Step 3: Idempotency check
        if (webhookEventRepository.existsByRazorpayEventId(eventId)) {
            log.info("Webhook duplicate detected, skipping: eventId={}", eventId);
            // Still mark as SKIPPED in the event store
            webhookEventRepository.findByRazorpayEventId(eventId).ifPresent(evt -> {
                if (evt.getStatus() == WebhookStatus.PENDING) {
                    evt.setStatus(WebhookStatus.SKIPPED);
                    webhookEventRepository.save(evt);
                }
            });
            return;
        }

        // Step 4: Store the event (PENDING state)
        WebhookEvent event = WebhookEvent.builder()
                .razorpayEventId(eventId)
                .eventType(eventType)
                .payload(rawPayload)
                .status(WebhookStatus.PENDING)
                .signatureVerified(true)  // We verified above
                .receivedAt(Instant.now())
                .build();
        event = webhookEventRepository.save(event);

        // Step 5: Process based on event type
        try {
            processEventByType(eventType, payload);
            event.setStatus(WebhookStatus.PROCESSED);
            event.setProcessedAt(Instant.now());
        } catch (Exception e) {
            log.error("Webhook processing failed: eventId={}, error={}", eventId, e.getMessage(), e);
            event.setStatus(WebhookStatus.FAILED);
            event.setErrorMessage(e.getMessage());
        }

        webhookEventRepository.save(event);
    }

    // ── Event type handlers ───────────────────────────────────────────────────

    private void processEventByType(String eventType, JSONObject payload) {
        switch (eventType) {
            case "payment.captured" -> handlePaymentCaptured(payload);
            case "payment.failed"   -> handlePaymentFailed(payload);
            case "refund.created"   -> handleRefundCreated(payload);
            case "refund.processed" -> handleRefundProcessed(payload);
            default -> log.info("Webhook: unhandled event type '{}' — logged and skipped", eventType);
        }
    }

    private void handlePaymentCaptured(JSONObject payload) {
        JSONObject paymentData = payload
                .optJSONObject("payload")
                .optJSONObject("payment")
                .optJSONObject("entity");

        if (paymentData == null) {
            log.warn("payment.captured webhook missing payment entity");
            return;
        }

        String razorpayPaymentId = paymentData.optString("id");
        String razorpayOrderId   = paymentData.optString("order_id");

        log.info("Webhook payment.captured: paymentId={}, orderId={}",
                razorpayPaymentId, razorpayOrderId);

        Map<String, Object> dataMap = jsonToMap(paymentData);
        paymentService.handleWebhookCapture(razorpayPaymentId, razorpayOrderId, dataMap);
    }

    private void handlePaymentFailed(JSONObject payload) {
        JSONObject paymentData = payload
                .optJSONObject("payload")
                .optJSONObject("payment")
                .optJSONObject("entity");

        if (paymentData == null) return;

        String razorpayPaymentId = paymentData.optString("id");
        String razorpayOrderId   = paymentData.optString("order_id");

        log.info("Webhook payment.failed: paymentId={}, orderId={}",
                razorpayPaymentId, razorpayOrderId);

        paymentService.handleWebhookFailure(razorpayPaymentId, razorpayOrderId, jsonToMap(paymentData));
    }

    private void handleRefundCreated(JSONObject payload) {
        // Refund webhook — logged for now, full handling in M6
        log.info("Webhook refund.created received — processing logged for M6 implementation");
    }

    private void handleRefundProcessed(JSONObject payload) {
        log.info("Webhook refund.processed received");
    }

    private Map<String, Object> jsonToMap(JSONObject json) {
        Map<String, Object> map = new HashMap<>();
        for (String key : json.keySet()) {
            map.put(key, json.get(key));
        }
        return map;
    }
}
