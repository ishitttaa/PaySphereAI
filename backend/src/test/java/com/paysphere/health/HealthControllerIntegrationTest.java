package com.paysphere.health;

import com.paysphere.BaseIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration Test: HealthController
 *
 * These tests verify Milestone 1 Acceptance Tests (AT-1 through AT-3):
 *
 * AT-1: GET /api/v1/health returns HTTP 200 with status:"UP"
 * AT-2: Application starts with environment-based DB configuration
 *       (proven by the fact that this test starts at all)
 * AT-3: Flyway V1 migration created the users table
 *       (proven by Hibernate validation succeeding — ddl-auto: validate)
 *
 * HOW TO READ THIS TEST FILE:
 * - @DisplayName → human-readable test name in reports
 * - assertThat()  → AssertJ fluent assertions (more readable than JUnit assertEquals)
 * - ResponseEntity → wraps the HTTP response (status code + body + headers)
 *
 * WHY integration tests over unit tests here?
 * The health endpoint is trivial — it doesn't need a unit test.
 * What MATTERS is that the whole stack (Spring Boot + DB + Flyway) works together.
 * That's what this integration test proves.
 */
@DisplayName("HealthController Integration Tests")
class HealthControllerIntegrationTest extends BaseIntegrationTest {

    /**
     * TestRestTemplate: an HTTP client that makes real HTTP calls to the app.
     * Spring Boot injects this automatically in @SpringBootTest with RANDOM_PORT.
     * It handles the random port selection for us.
     */
    @Autowired
    private TestRestTemplate restTemplate;

    /**
     * AT-1: Health endpoint returns HTTP 200 with correct body.
     *
     * We use Map.class here to avoid coupling the test to ApiResponse<>.
     * In later tests we'll use typed response objects.
     */
    @Test
    @DisplayName("AT-1: GET /api/v1/health returns 200 OK with status UP")
    void healthEndpoint_returns200WithStatusUp() {
        // When: we call the health endpoint
        ResponseEntity<String> response = restTemplate.getForEntity(
                "/api/v1/health",
                String.class
        );

        // Then: status is 200 OK
        assertThat(response.getStatusCode())
                .as("Health endpoint should return HTTP 200")
                .isEqualTo(HttpStatus.OK);

        // And: body contains "UP"
        assertThat(response.getBody())
                .as("Response body should indicate status UP")
                .contains("\"UP\"");

        // And: body contains "success":true
        assertThat(response.getBody())
                .as("Response should use standard ApiResponse envelope with success:true")
                .contains("\"success\":true");

        // And: body contains a version field
        assertThat(response.getBody())
                .as("Response should include application version")
                .contains("\"version\"");
    }

    /**
     * AT-2 & AT-3: If this test runs at all, it proves:
     * - AT-2: The app started successfully with environment-based DB config
     *         (Testcontainers injected DB_URL, DB_USER, DB_PASSWORD dynamically)
     * - AT-3: Flyway V1 migration ran and Hibernate validated the schema
     *         (ddl-auto:validate would throw if tables didn't match entities)
     *
     * We add an explicit assertion about the response Content-Type.
     */
    @Test
    @DisplayName("AT-2 & AT-3: Application started with DB and Flyway migration applied")
    void application_startsSuccessfully_withDatabaseAndMigrations() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                "/api/v1/health",
                String.class
        );

        // The application started and responded — DB and Flyway are working
        assertThat(response.getStatusCode().is2xxSuccessful())
                .as("A 2xx response proves the application started with DB connected")
                .isTrue();

        // Content-Type should be application/json
        assertThat(response.getHeaders().getContentType())
                .as("Health endpoint should return JSON")
                .isNotNull()
                .satisfies(ct -> assertThat(ct.toString()).contains("application/json"));
    }

    /**
     * AT-6: The health response must not contain any credential-like strings.
     *
     * This is a basic secret-leakage check. We verify that common secret
     * patterns (key=, secret=, password=) are not in the response body.
     * A more thorough check would scan all log output — that comes later.
     */
    @Test
    @DisplayName("AT-6: Health response does not expose credentials or secrets")
    void healthEndpoint_doesNotExposeCredentials() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                "/api/v1/health",
                String.class
        );

        String body = response.getBody();
        assertThat(body).as("Health response must not contain 'password'").doesNotContainIgnoringCase("password");
        assertThat(body).as("Health response must not contain 'secret'").doesNotContainIgnoringCase("secret");
        assertThat(body).as("Health response must not contain 'key_id'").doesNotContainIgnoringCase("key_id");
        assertThat(body).as("Health response must not contain database URL").doesNotContainIgnoringCase("jdbc:");
    }
}
