package com.paysphere.gateway;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GatewayExceptionTest {

    @Test
    void clientErrorsAreDefiniteRejections() {
        for (int status : new int[]{400, 401, 403, 404, 422, 429}) {
            assertThat(GatewayException.fromHttpStatus(status, "x").isDefinitiveRejection())
                    .as("HTTP " + status).isTrue();
        }
    }

    @Test
    void requestTimeoutAndConflictAreUncertain() {
        assertThat(GatewayException.fromHttpStatus(408, "x").isDefinitiveRejection()).isFalse();
        assertThat(GatewayException.fromHttpStatus(409, "x").isDefinitiveRejection()).isFalse();
    }

    @Test
    void serverErrorsAreUncertain() {
        for (int status : new int[]{500, 502, 503, 504}) {
            assertThat(GatewayException.fromHttpStatus(status, "x").isDefinitiveRejection())
                    .as("HTTP " + status).isFalse();
        }
    }

    @Test
    void longResponseBodiesAreShortenedInTheMessage() {
        GatewayException e = GatewayException.fromHttpStatus(400, "a".repeat(1000));
        assertThat(e.getMessage().length()).isLessThan(400);
    }

    @Test
    void theHttpStatusIsKept() {
        assertThat(GatewayException.fromHttpStatus(422, "x").getHttpStatus()).isEqualTo(422);
    }
}