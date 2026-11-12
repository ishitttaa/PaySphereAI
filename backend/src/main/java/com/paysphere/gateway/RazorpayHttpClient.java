package com.paysphere.gateway;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

/**
 * Thin HTTP client for Razorpay REST API calls.
 *
 * Uses Java 11+ HttpClient (no third-party HTTP library needed).
 * Handles Basic Auth (API key:secret as Base64), JSON content-type,
 * and idempotency key header injection.
 *
 * SECURITY: keySecret is used in Basic Auth but NEVER logged.
 * The Authorization header is built and used in memory only.
 */
class RazorpayHttpClient {

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private static final int READ_TIMEOUT_SECONDS = 30;

    private RazorpayHttpClient() {}

    /**
     * POST request to Razorpay API.
     *
     * @param url             Full URL (e.g., https://api.razorpay.com/v1/orders)
     * @param jsonBody        JSON request body
     * @param keyId           Razorpay key ID
     * @param keySecret       Razorpay key secret (NEVER logged)
     * @param idempotencyKey  Unique key to prevent duplicate operations
     * @return Response body as String
     */
    static String post(String url, String jsonBody, String keyId,
                        String keySecret, String idempotencyKey) throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(READ_TIMEOUT_SECONDS))
                .header("Content-Type", "application/json")
                .header("Authorization", buildBasicAuth(keyId, keySecret))
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8));

        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            builder.header("X-Idempotency-Key", idempotencyKey);
        }

        HttpResponse<String> response = HTTP_CLIENT.send(
                builder.build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
        );

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Razorpay API error: HTTP " + response.statusCode()
                    + " → " + response.body());
        }

        return response.body();
    }

    /**
     * GET request to Razorpay API.
     */
    static String get(String url, String keyId, String keySecret) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(READ_TIMEOUT_SECONDS))
                .header("Authorization", buildBasicAuth(keyId, keySecret))
                .GET()
                .build();

        HttpResponse<String> response = HTTP_CLIENT.send(
                request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Razorpay API error: HTTP " + response.statusCode()
                    + " → " + response.body());
        }

        return response.body();
    }

    /**
     * Builds the Basic Auth header value.
     * Format: "Basic " + Base64(keyId:keySecret)
     *
     * SECURITY: This string contains the secret. It exists only in memory
     * during the HTTP call. Never assign to a field or log it.
     */
    private static String buildBasicAuth(String keyId, String keySecret) {
        String credentials = keyId + ":" + keySecret;
        return "Basic " + Base64.getEncoder().encodeToString(
                credentials.getBytes(StandardCharsets.UTF_8));
    }
}
