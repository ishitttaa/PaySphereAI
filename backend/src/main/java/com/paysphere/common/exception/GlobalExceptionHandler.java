package com.paysphere.common.exception;

import com.paysphere.common.response.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

/**
 * Global exception handler — catches exceptions from ANY @RestController.
 *
 * Instead of letting Spring return its default error format (which leaks
 * internal stack traces), we map exceptions to our ApiResponse envelope.
 *
 * WHY centralized?
 * - No try/catch boilerplate in every controller
 * - Consistent error format for the frontend to parse
 * - One place to control what error details are safe to expose
 *
 * SECURITY NOTE: Never include stack traces or internal class names in the
 * response body — these help attackers understand the system internals.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * Handles @Valid validation failures on request DTOs.
     * Returns HTTP 400 with a readable message listing all field errors.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidationException(
            MethodArgumentNotValidException ex) {

        String errors = ex.getBindingResult()
                .getFieldErrors()
                .stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .collect(Collectors.joining(", "));

        log.warn("Validation failed: {}", errors);
        return ResponseEntity.badRequest().body(ApiResponse.failure(errors));
    }

    /**
     * Handles resource not found — returns HTTP 404.
     */
    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleResourceNotFoundException(ResourceNotFoundException ex) {
        log.warn("Resource not found: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.failure(ex.getMessage()));
    }
    
    @ExceptionHandler(InvalidRequestException.class)
    public ResponseEntity<ApiResponse<Void>> handleInvalidRequest(InvalidRequestException ex) {
        log.warn("Invalid request [{}]: {}", ex.getErrorCode(), ex.getMessage());
        return ResponseEntity.badRequest().body(ApiResponse.failure(ex.getMessage()));
    }
    /**
     * Handles business rule violations — returns HTTP 409 Conflict.
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusinessException(BusinessException ex) {
        log.warn("Business rule violation [{}]: {}", ex.getErrorCode(), ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.failure(ex.getMessage()));
    }

    /**
     * Handles payment signature verification failures — returns HTTP 400.
     *
     * SECURITY: We log only a generic message, never the raw signature.
     */
    @ExceptionHandler(SignatureVerificationException.class)
    public ResponseEntity<ApiResponse<Void>> handleSignatureVerificationException(
            SignatureVerificationException ex) {
        log.warn("Payment signature verification failed");
        return ResponseEntity.badRequest()
                .body(ApiResponse.failure("Payment verification failed. Please contact support."));
    }

    /**
     * Catch-all handler for any unexpected exception.
     * Logs the full details server-side, but returns a generic message to the client.
     *
     * IMPORTANT: The client sees "An unexpected error occurred" — not the real exception.
     * Real details are in server logs where only authorized people can see them.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleGenericException(Exception ex) {
        // Log full detail for debugging — but only server-side
        log.error("Unhandled exception: {}", ex.getMessage(), ex);

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.failure("An unexpected error occurred. " +
                        "Please contact support if this persists."));
    }
}
