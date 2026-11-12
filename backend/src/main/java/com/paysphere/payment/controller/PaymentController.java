package com.paysphere.payment.controller;

import com.paysphere.common.response.ApiResponse;
import com.paysphere.payment.dto.PaymentResponse;
import com.paysphere.payment.dto.VerifyPaymentRequest;
import com.paysphere.payment.service.PaymentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Payment Controller — REST endpoints for payment verification and queries.
 *
 * POST /api/v1/payments/verify   Verify payment after Razorpay checkout (M3)
 * GET  /api/v1/payments          List payments
 * GET  /api/v1/payments/{id}     Get payment by UUID
 * GET  /api/v1/payments/order/{orderId}  Get all payments for an order
 */
@RestController
@RequestMapping("/api/v1/payments")
@Tag(name = "Payments", description = "Payment verification and lifecycle management")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;

    /**
     * POST /api/v1/payments/verify
     *
     * Called by frontend after customer completes Razorpay checkout.
     * Verifies HMAC-SHA256 signature, fetches payment details, and records capture.
     *
     * This is the critical M3 endpoint — server-side signature verification.
     */
    @PostMapping("/verify")
    @Operation(summary = "Verify payment signature",
               description = "Verifies the Razorpay payment signature after checkout completion. " +
                             "Must be called server-side — never trust client-provided status.")
    public ResponseEntity<ApiResponse<PaymentResponse>> verifyPayment(
            @Valid @RequestBody VerifyPaymentRequest request) {
        PaymentResponse payment = paymentService.verifyAndCapture(request);
        return ResponseEntity.ok(ApiResponse.success(payment));
    }

    /**
     * GET /api/v1/payments
     */
    @GetMapping
    @Operation(summary = "List payments")
    public ResponseEntity<ApiResponse<Page<PaymentResponse>>> listPayments(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(ApiResponse.success(
                paymentService.listPayments(PageRequest.of(page, Math.min(size, 100)))));
    }

    /**
     * GET /api/v1/payments/{id}
     */
    @GetMapping("/{id}")
    @Operation(summary = "Get payment by ID")
    public ResponseEntity<ApiResponse<PaymentResponse>> getPaymentById(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.success(paymentService.getPaymentById(id)));
    }

    /**
     * GET /api/v1/payments/order/{orderId}
     * Returns all payment attempts for a given business order.
     */
    @GetMapping("/order/{orderId}")
    @Operation(summary = "Get payments for an order")
    public ResponseEntity<ApiResponse<List<PaymentResponse>>> getPaymentsForOrder(
            @PathVariable UUID orderId) {
        return ResponseEntity.ok(ApiResponse.success(
                paymentService.getPaymentsForOrder(orderId)));
    }
}
