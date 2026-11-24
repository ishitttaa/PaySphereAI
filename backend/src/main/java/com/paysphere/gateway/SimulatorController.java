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
                             String method, Boolean international) {}

    @PostMapping("/pay")
    public ResponseEntity<ApiResponse<Map<String, Object>>> pay(@Valid @RequestBody PayRequest request) {
        boolean success = !"FAILURE".equalsIgnoreCase(request.outcome());
        String method = request.method() != null ? request.method() : "card";
        boolean international = Boolean.TRUE.equals(request.international());

        return gateway.payOrder(request.razorpayOrderId(), success, method, international)
                .map(result -> ResponseEntity.ok(ApiResponse.success(result)))
                .orElseGet(() -> ResponseEntity.badRequest()
                        .body(ApiResponse.failure("Unknown simulated order")));
    }
        public record WebhookRequest(@NotBlank String eventType, String eventId,
                                 String refundId, String paymentId, Long amountPaise) {}

    /** Sends a correctly signed webhook to our own webhook handler. Currently: refund.processed. */
    @PostMapping("/webhook")
    public ResponseEntity<ApiResponse<Map<String, Object>>> webhook(@Valid @RequestBody WebhookRequest request) {
        if (!"refund.processed".equals(request.eventType())
                || request.refundId() == null || request.paymentId() == null
                || request.amountPaise() == null || request.amountPaise() <= 0) {
            return ResponseEntity.badRequest().body(ApiResponse.failure(
                    "Simulator supports eventType=refund.processed with refundId, paymentId and amountPaise"));
        }
        String eventId = request.eventId() != null ? request.eventId()
                : "evt_sim_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);

        JSONObject entity = new JSONObject()
                .put("id", request.refundId())
                .put("payment_id", request.paymentId())
                .put("amount", request.amountPaise())
                .put("currency", "INR")
                .put("status", "processed");
        JSONObject body = new JSONObject()
                .put("entity", "event")
                .put("event", request.eventType())
                .put("id", eventId)
                .put("payload", new JSONObject().put("refund", new JSONObject().put("entity", entity)));
        String raw = body.toString();

        webhookService.processWebhook(raw, gateway.signWebhook(raw));
        return ResponseEntity.ok(ApiResponse.success(Map.of("eventId", eventId, "delivered", true)));
    }
}