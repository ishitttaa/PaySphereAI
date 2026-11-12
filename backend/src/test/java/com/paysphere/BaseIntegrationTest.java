package com.paysphere;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Base class for all PaySphere integration tests that need a real database.
 *
 * HOW TESTCONTAINERS WORKS:
 * 1. Before any test runs, Testcontainers starts a real PostgreSQL Docker container.
 * 2. Spring Boot connects to that container (via the dynamic properties below).
 * 3. Flyway runs all migrations against the fresh container database.
 * 4. Tests run against the real schema — just like production.
 * 5. Container is destroyed after all tests complete.
 *
 * WHY NOT mock the database?
 * - H2 in-memory database has different SQL dialect than PostgreSQL
 * - PostgreSQL-specific features (UUID, TIMESTAMPTZ, CHECK constraints) don't work in H2
 * - Real database tests catch real bugs: wrong column types, missing indexes, etc.
 *
 * WHY static @Container?
 * - "static" means one container is shared across ALL test methods in a test class.
 * - Without static, a new container starts for every test method — very slow.
 * - The @DirtiesContext / transaction rollback handles test isolation instead.
 *
 * IMPORTANT: This requires Docker to be running on the machine running the tests.
 * In CI (GitHub Actions), Docker is available by default.
 *
 * SECURITY GUARD: Prevents running tests with live Razorpay credentials.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
public abstract class BaseIntegrationTest {

    /**
     * PostgreSQL container shared across all tests extending this class.
     * "postgres:16-alpine" = PostgreSQL 16, minimal Alpine Linux base image.
     * Alpine is smaller (faster to pull) and has a smaller attack surface.
     */
    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("paysphere_test")
                    .withUsername("paysphere_test")
                    .withPassword("paysphere_test_password");

    /**
     * Dynamically inject the container's connection details into Spring's
     * application context BEFORE the context starts.
     *
     * This replaces the DATABASE_URL, DB_USER, DB_PASSWORD environment variables
     * with the Testcontainers-assigned host:port for the test database.
     */
    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);

        // Disable Razorpay validation during tests — keys not needed for DB tests
        registry.add("razorpay.key-id", () -> "rzp_test_placeholder");
        registry.add("razorpay.key-secret", () -> "test_secret_placeholder");
        registry.add("razorpay.webhook-secret", () -> "test_webhook_placeholder");
        registry.add("razorpay.mode", () -> "test");

        // JWT placeholder for tests
        registry.add("JWT_SECRET", () ->
                "test_jwt_secret_that_is_at_least_64_characters_long_for_testing_purposes_only_1234");
    }

    /**
     * SECURITY GUARD: Fail immediately if someone accidentally runs tests
     * with live Razorpay credentials. Test mode keys start with "rzp_test_".
     */
    static {
        String keyId = System.getenv("RAZORPAY_KEY_ID");
        if (keyId != null && keyId.startsWith("rzp_live_")) {
            throw new IllegalStateException(
                    "🚨 SECURITY: Integration tests must not run with live Razorpay credentials. " +
                    "Set RAZORPAY_KEY_ID to a test key (rzp_test_...) or unset it."
            );
        }
    }
}
