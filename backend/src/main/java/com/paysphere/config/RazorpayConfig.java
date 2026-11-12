package com.paysphere.config;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Razorpay Configuration Properties
 *
 * Reads razorpay.* from application.yml — all values come from
 * environment variables, never hardcoded.
 *
 * @PostConstruct validates that test/live mode keys don't cross-contaminate.
 * This is a critical safety guard to prevent accidental real charges.
 */
@Slf4j
@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "razorpay")
public class RazorpayConfig {

    private String keyId;
    private String keySecret;
    private String webhookSecret;
    private String mode;
    private String baseUrl;

    /**
     * Validates mode-key consistency on startup.
     * FATAL: live key in test mode, or test key in live mode → fail-fast.
     *
     * SECURITY: We deliberately check keyId but NEVER log keySecret.
     */
    @PostConstruct
    public void validate() {
        if (keyId == null || keyId.isBlank()) {
            log.warn("RAZORPAY_KEY_ID is not configured. Payment features will be unavailable.");
            return;
        }

        // Redact the key for logging — show only first 12 chars
        String redactedKeyId = keyId.length() > 12
                ? keyId.substring(0, 12) + "..." + "[REDACTED]"
                : "[REDACTED]";
        log.info("Razorpay configured: mode={}, keyId={}", mode, redactedKeyId);

        if ("live".equals(mode) && keyId.startsWith("rzp_test_")) {
            throw new IllegalStateException(
                    "FATAL: Live mode configured but test key detected. " +
                    "Check RAZORPAY_MODE and RAZORPAY_KEY_ID environment variables."
            );
        }

        if ("test".equals(mode) && keyId.startsWith("rzp_live_")) {
            throw new IllegalStateException(
                    "FATAL: Test mode configured but live key detected. " +
                    "Check RAZORPAY_MODE and RAZORPAY_KEY_ID environment variables."
            );
        }
    }

    /**
     * Returns true if this is Razorpay test mode.
     * Use this for conditional logic that should only run in test mode.
     */
    public boolean isTestMode() {
        return "test".equals(mode);
    }

    /**
     * Returns the key ID — safe to return (not a secret).
     * Used by the frontend to initialize Razorpay Checkout.
     */
    public String getPublicKeyId() {
        return keyId;
    }
}
