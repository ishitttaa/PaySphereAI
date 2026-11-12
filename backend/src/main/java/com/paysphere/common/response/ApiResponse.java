package com.paysphere.common.response;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * Standard API response envelope for ALL PaySphere endpoints.
 *
 * Every response follows this shape:
 * {
 *   "success": true,
 *   "data": { ... },       ← present on success
 *   "error": null,         ← present on failure
 *   "timestamp": "...",
 *   "requestId": "..."     ← for log correlation (added later)
 * }
 *
 * WHY a standard envelope?
 * - The frontend can always check `success` first before parsing `data`
 * - Error handling is consistent across every endpoint
 * - Makes API versioning easier
 *
 * @param <T> the type of data payload (e.g., OrderResponse, PaymentResponse)
 */
@JsonInclude(JsonInclude.Include.NON_NULL) // omit null fields from JSON output
public record ApiResponse<T>(
        boolean success,
        T data,
        String error,
        Instant timestamp
) {

    /**
     * Build a successful response with a data payload.
     * Usage: return ResponseEntity.ok(ApiResponse.success(orderDto));
     */
    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(true, data, null, Instant.now());
    }

    /**
     * Build a failure response with an error message.
     * Usage: return ResponseEntity.badRequest().body(ApiResponse.failure("Invalid amount"));
     */
    public static <T> ApiResponse<T> failure(String error) {
        return new ApiResponse<>(false, null, error, Instant.now());
    }
}
