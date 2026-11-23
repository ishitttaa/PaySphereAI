package com.paysphere;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Base class for integration tests.
 *
 * One PostgreSQL container is started ONCE for the whole test run and shared by every
 * integration test class (it is stopped automatically when the JVM exits). Spring caches
 * the application context between test classes, so the database it points at must stay
 * alive for the whole run. A container per class breaks that cache.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class BaseIntegrationTest {

    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("paysphere_test")
                    .withUsername("paysphere_test")
                    .withPassword("paysphere_test_password");

    static {
        String keyId = System.getenv("RAZORPAY_KEY_ID");
        if (keyId != null && keyId.startsWith("rzp_live_")) {
            throw new IllegalStateException(
                    "SECURITY: Integration tests must not run with live Razorpay credentials. "
                    + "Set RAZORPAY_KEY_ID to a test key (rzp_test_...) or unset it.");
        }
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);

        registry.add("razorpay.key-id", () -> "rzp_test_placeholder");
        registry.add("razorpay.key-secret", () -> "test_secret_placeholder");
        registry.add("razorpay.webhook-secret", () -> "test_webhook_placeholder");
        registry.add("razorpay.mode", () -> "test");

        registry.add("JWT_SECRET", () ->
                "test_jwt_secret_that_is_at_least_64_characters_long_for_testing_purposes_only_1234");
    }
}