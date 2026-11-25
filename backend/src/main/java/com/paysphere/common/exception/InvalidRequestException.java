package com.paysphere.common.exception;

/** The caller sent something unusable (for example a missing header). Answered with HTTP 400. */
public class InvalidRequestException extends RuntimeException {

    private final String errorCode;

    public InvalidRequestException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}