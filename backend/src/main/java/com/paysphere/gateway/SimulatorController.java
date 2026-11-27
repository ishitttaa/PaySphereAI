package com.paysphere.gateway;

import com.paysphere.common.response.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.paysphere.webhook.service.WebhookService;
import org.json.JSONObject;
import java.util.UUID;

import java.util.Map;

/** Exists ONLY when PAYMENT_GATEWAY=simulator. Plays the part of the checkout popup. */
@RestController
@RequestMapping("/api/v1/simulator")
@ConditionalOnProperty(name = "payment.gateway", havingValue = "simulator")
@RequiredArgsConstructor
public class SimulatorController {

    private final SimulatedGateway gateway;
    private final WebhookService webhookService;

    public record PayRequest(@NotBlank String razorpayOrderId, String outcome,
                             String method, Boolean international, Integer minutesAgo) {}

    @PostMapping("/pay")
    public ResponseEntity<ApiResponse<Map<String, Object>>> pay(@Valid @RequestBody PayRequest request) {
        boolean success = !"FAILURE".equalsIgnoreCase(request.outcome());
        String method = request.method() != null ? request.method() : "card";
        boolean international = Boolean.TRUE.equals(request.international());
        int minutesAgo = request.minutesAgo() == null ? 0 : Math.max(0, request.minutesAgo());

        return gateway.payOrder(request.razorpayOrderId(), success, method, international, minutesAgo)
                .map(result -> ResponseEntity.ok(ApiResponse.success(result)))
                .orElseGet(() -> ResponseEntity.badRequest()
                        .body(ApiResponse.failure("Unknown simulated order")));
    }
    public record WebhookRequest(@NotBlank String eventType, String eventId,
                                 String refundId, String paymentId, Long amountPaise,
                                 String razorpayOrderId) {}

    /**
     * Sends a correctly signed webhook to our own webhook handler:
     * refund.processed / refund.failed (needs refundId, paymentId, amountPaise) or
     * payment.failed / payment.captured (needs paymentId, razorpayOrderId, amountPaise).
     */
    @PostMapping("/webhook")
    public ResponseEntity<ApiResponse<Map<String, Object>>> webhook(@Valid @RequestBody WebhookRequest request) {
        String type = request.eventType();
        boolean refundEvent = "refund.processed".equals(type) || "refund.failed".equals(type);
        boolean paymentEvent = "payment.failed".equals(type) || "payment.captured".equals(type);
        boolean valid = (refundEvent || paymentEvent)
                && request.paymentId() != null
                && request.amountPaise() != null && request.amountPaise() > 0
                && (refundEvent ? request.refundId() != null : request.razorpayOrderId() != null);
        if (!valid) {
            return ResponseEntity.badRequest().body(ApiResponse.failure(
                    "Simulator supports refund.processed / refund.failed (refundId, paymentId, amountPaise) "
                            + "and payment.failed / payment.captured (paymentId, razorpayOrderId, amountPaise)"));
        }
        String eventId = request.eventId() != null ? request.eventId()
                : "evt_sim_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);

        JSONObject payload = new JSONObject();
        if (refundEvent) {
            JSONObject entity = new JSONObject()
                    .put("id", request.refundId())
                    .put("payment_id", request.paymentId())
                    .put("amount", request.amountPaise())
                    .put("currency", "INR")
                    .put("status", "refund.failed".equals(type) ? "failed" : "processed");
            payload.put("refund", new JSONObject().put("entity", entity));
        } else {
            boolean failed = "payment.failed".equals(type);
            JSONObject entity = new JSONObject()
                    .put("id", request.paymentId())
                    .put("entity", "payment")
                    .put("order_id", request.razorpayOrderId())
                    .put("amount", request.amountPaise())
                    .put("currency", "INR")
                    .put("method", "card")
                    .put("status", failed ? "failed" : "captured")
                    .put("captured", !failed);
            if (failed) {
                entity.put("error_code", "BAD_REQUEST_ERROR")
                        .put("error_description", "Simulated payment failure")
                        .put("error_source", "bank")
                        .put("error_step", "payment_authorization")
                        .put("error_reason", "payment_failed");
            }
            payload.put("payment", new JSONObject().put("entity", entity));
        }
        JSONObject body = new JSONObject()
                .put("entity", "event")
                .put("event", type)
                .put("id", eventId)
                .put("payload", payload);
        String raw = body.toString();

        webhookService.processWebhook(raw, gateway.signWebhook(raw));
        return ResponseEntity.ok(ApiResponse.success(Map.of("eventId", eventId, "delivered", true)));
    }
    public record RefundModeRequest(@NotBlank String mode) {}

    /** Makes the simulator's refund calls misbehave: NORMAL, TIMEOUT_AFTER_SUCCESS or TIMEOUT_NO_EFFECT. */
    @PostMapping("/refund-mode")
    public ResponseEntity<ApiResponse<Map<String, Object>>> refundMode(@Valid @RequestBody RefundModeRequest request) {
        try {
            gateway.setRefundMode(SimulatedGateway.RefundMode.valueOf(request.mode().toUpperCase()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(ApiResponse.failure(
                    "mode must be NORMAL, TIMEOUT_AFTER_SUCCESS or TIMEOUT_NO_EFFECT"));
        }
        return ResponseEntity.ok(ApiResponse.success(Map.of("mode", request.mode().toUpperCase())));
    }
}