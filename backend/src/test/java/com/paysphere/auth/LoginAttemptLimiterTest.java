package com.paysphere.auth;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class LoginAttemptLimiterTest {

    static class MutableClock extends Clock {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    private final MutableClock clock = new MutableClock();
    private final LoginAttemptLimiter limiter = new LoginAttemptLimiter(clock);

    @Test
    void blocksAfterTheLimitIsReached() {
        for (int i = 0; i < 4; i++) {
            limiter.recordFailure("k");
        }
        assertThat(limiter.isBlocked("k", 5)).isFalse();
        limiter.recordFailure("k");
        assertThat(limiter.isBlocked("k", 5)).isTrue();
    }

    @Test
    void successfulLoginClearsTheCounter() {
        for (int i = 0; i < 5; i++) {
            limiter.recordFailure("k");
        }
        limiter.recordSuccess("k");
        assertThat(limiter.isBlocked("k", 5)).isFalse();
    }

    @Test
    void blockExpiresAfterFifteenMinutes() {
        for (int i = 0; i < 5; i++) {
            limiter.recordFailure("k");
        }
        assertThat(limiter.isBlocked("k", 5)).isTrue();
        clock.now = clock.now.plus(Duration.ofMinutes(16));
        assertThat(limiter.isBlocked("k", 5)).isFalse();
    }

    @Test
    void differentKeysDoNotAffectEachOther() {
        for (int i = 0; i < 5; i++) {
            limiter.recordFailure("a");
        }
        assertThat(limiter.isBlocked("b", 5)).isFalse();
    }
}