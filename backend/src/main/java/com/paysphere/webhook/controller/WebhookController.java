package com.paysphere.webhook.controller;

import com.paysphere.common.response.ApiResponse;
import com.paysphere.webhook.service.WebhookService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Webhook Controller — receives and dispatches Razorpay webhook events.
 *
 * POST /api/v1/webhooks/razorpay
 *
 * SECURITY requirements for this endpoint:
 * 1. Read raw body as String BEFORE any framework processing
 *    → @RequestBody String gives us the raw JSON
 * 2. Verify X-Razorpay-Signature header BEFORE processing payload
 * 3. Always return HTTP 200 quickly — Razorpay retries on non-200 responses
 *    (up to 3 retries, so idempotency handling is critical)
 * 4. This endpoint must be publicly accessible (no auth) — Razorpay doesn't
 *    send auth tokens; signature verification IS the auth mechanism
 *
 * NOTE: In a high-throughput system, we'd queue the webhook to a message broker
 * (Kafka) and process asynchronously. For M5, synchronous processing is fine.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/webhooks")
@Tag(name = "Webhooks", description = "Razorpay webhook event receiver")
@RequiredArgsConstructor
public class WebhookController {

    private final WebhookService webhookService;

    /**
     * POST /api/v1/webhooks/razorpay
     *
     * Razorpay sends POST requests here for every payment event.
     * We must respond with HTTP 200 within a few seconds or Razorpay retries.
     *
     * The raw body must be verified BEFORE parsing to prevent signature bypass.
     */
    @PostMapping("/razorpay")
    @Operation(summary = "Razorpay webhook receiver",
               description = "Receives payment events from Razorpay. " +
                             "Signature verification happens before any processing.")
    public ResponseEntity<ApiResponse<String>> receiveWebhook(
            @RequestBody String rawPayload,
            @RequestHeader(value = "X-Razorpay-Signature", required = false) String signature) {

        log.info("Webhook received from Razorpay, signature present: {}", signature != null);

        if (signature == null || signature.isBlank()) {
            log.warn("Webhook received without X-Razorpay-Signature header. Rejecting.");
            return ResponseEntity.badRequest()
                    .body(ApiResponse.failure("Missing webhook signature"));
        }

        try {
            webhookService.processWebhook(rawPayload, signature);
            return ResponseEntity.ok(ApiResponse.success("OK"));
        } catch (SecurityException e) {
            // Signature verification failed — log and return 400
            // Don't tell the caller WHY it failed (information leak)
            log.warn("Webhook rejected: invalid signature");
            return ResponseEntity.badRequest()
                    .body(ApiResponse.failure("Invalid webhook signature"));
        }catch (Exception e) {
            // Processing failed. Return 500 so Razorpay retries delivery.
            log.error("Webhook processing error: {}", e.getMessage(), e);
            return ResponseEntity.status(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResponse.failure("Processing failed, please retry"));
        }
    }
}
