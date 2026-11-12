package com.paysphere.common.exception;

/**
 * Thrown when a payment signature verification fails.
 * Maps to HTTP 400 in GlobalExceptionHandler.
 *
 * SECURITY: This exception is never logged with the full signature value —
 * only a redacted version. The client receives a generic message.
 */
public class SignatureVerificationException extends RuntimeException {

    public SignatureVerificationException(String message) {
        super(message);
    }
}
