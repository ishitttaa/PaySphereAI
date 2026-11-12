package com.paysphere.common.exception;

/**
 * Thrown when a business rule is violated.
 * Examples: duplicate refund, payment already captured, invalid state transition.
 * Maps to HTTP 409 (Conflict) in GlobalExceptionHandler.
 */
public class BusinessException extends RuntimeException {

    private final String errorCode;

    public BusinessException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
