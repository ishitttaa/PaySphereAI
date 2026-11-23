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

import java.util.Map;

/** Exists ONLY when PAYMENT_GATEWAY=simulator. Plays the part of the checkout popup. */
@RestController
@RequestMapping("/api/v1/simulator")
@ConditionalOnProperty(name = "payment.gateway", havingValue = "simulator")
@RequiredArgsConstructor
public class SimulatorController {

    private final SimulatedGateway gateway;

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
}