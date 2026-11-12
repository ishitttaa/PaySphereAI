package com.paysphere.refund.controller;

import com.paysphere.common.response.ApiResponse;
import com.paysphere.refund.dto.CreateRefundRequest;
import com.paysphere.refund.dto.RefundResponse;
import com.paysphere.refund.service.RefundService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Refund Controller — REST endpoints for refund management.
 *
 * POST /api/v1/refunds              Initiate a refund
 * GET  /api/v1/refunds/{id}         Get refund by ID
 * GET  /api/v1/refunds/payment/{paymentId}  Get all refunds for a payment
 * GET  /api/v1/refunds/order/{orderId}      Get all refunds for an order
 */
@RestController
@RequestMapping("/api/v1/refunds")
@Tag(name = "Refunds", description = "Refund initiation and tracking")
@RequiredArgsConstructor
public class RefundController {

    private final RefundService refundService;

    @PostMapping
    @Operation(summary = "Initiate a refund",
               description = "Creates a refund for a CAPTURED payment. Supports partial refunds.")
    public ResponseEntity<ApiResponse<RefundResponse>> createRefund(
            @Valid @RequestBody CreateRefundRequest request) {
        RefundResponse refund = refundService.createRefund(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(refund));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get refund by ID")
    public ResponseEntity<ApiResponse<RefundResponse>> getRefundById(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.success(refundService.getRefundById(id)));
    }

    @GetMapping("/payment/{paymentId}")
    @Operation(summary = "Get refunds for a payment")
    public ResponseEntity<ApiResponse<List<RefundResponse>>> getRefundsForPayment(
            @PathVariable UUID paymentId) {
        return ResponseEntity.ok(ApiResponse.success(refundService.getRefundsForPayment(paymentId)));
    }

    @GetMapping("/order/{orderId}")
    @Operation(summary = "Get refunds for an order")
    public ResponseEntity<ApiResponse<List<RefundResponse>>> getRefundsForOrder(
            @PathVariable UUID orderId) {
        return ResponseEntity.ok(ApiResponse.success(refundService.getRefundsForOrder(orderId)));
    }
}
