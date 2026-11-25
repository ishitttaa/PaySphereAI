package com.paysphere.gateway;

/**
 * Thrown by payment gateway adapters. It says whether the gateway DEFINITELY did not act
 * (REJECTED, for example a clear HTTP 4xx) or whether we do not know what happened (UNKNOWN,
 * for example a timeout, a network error or an HTTP 5xx).
 *
 * Callers that move money must treat UNKNOWN as "maybe it happened": never record a failure
 * on a guess, wait for a webhook or reconciliation instead.
 */
public class GatewayException extends RuntimeException {

    public enum Outcome { REJECTED, UNKNOWN }

    private final Outcome outcome;
    private final Integer httpStatus;

    public GatewayException(Outcome outcome, Integer httpStatus, String message, Throwable cause) {
        super(message, cause);
        this.outcome = outcome;
        this.httpStatus = httpStatus;
    }

    public boolean isDefinitiveRejection() {
        return outcome == Outcome.REJECTED;
    }

    public Outcome getOutcome() {
        return outcome;
    }

    public Integer getHttpStatus() {
        return httpStatus;
    }

    /**
     * 4xx means the request was refused, so nothing happened, EXCEPT 408 (request timeout) and
     * 409 (a request with the same idempotency key may still be running): those are uncertain.
     * 5xx and anything else is uncertain.
     */
    public static GatewayException fromHttpStatus(int status, String responseBody) {
        boolean rejected = status >= 400 && status < 500 && status != 408 && status != 409;
        String body = responseBody == null ? "" : responseBody;
        if (body.length() > 300) {
            body = body.substring(0, 300) + "...";
        }
        return new GatewayException(
                rejected ? Outcome.REJECTED : Outcome.UNKNOWN,
                status,
                "Gateway API error: HTTP " + status + " -> " + body,
                null);
    }
}