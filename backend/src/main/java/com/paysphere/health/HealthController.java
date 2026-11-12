package com.paysphere.health;

import com.paysphere.common.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Health Check Controller
 *
 * Exposes GET /api/v1/health — a lightweight, unauthenticated endpoint
 * used by load balancers, monitoring systems, and Docker health checks.
 *
 * WHY not just use Spring Actuator's /actuator/health?
 * - Actuator exposes much more detail (DB status, disk space, etc.)
 * - We'll lock down /actuator to internal networks in production
 * - This endpoint is intentionally simple and always-available
 * - It uses our standard ApiResponse envelope so the frontend can also use it
 *
 * @RestController = @Controller + @ResponseBody
 *   → Every method return value is serialized to JSON automatically
 *
 * @RequestMapping("/api/v1") → base path for all methods in this class
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Health", description = "Application health and version check")
public class HealthController {

    // Injected from application.yml → info.app.version
    @Value("${info.app.version:1.0.0}")
    private String appVersion;

    /**
     * Returns the current application status and version.
     *
     * Response example:
     * {
     *   "success": true,
     *   "data": {
     *     "status": "UP",
     *     "version": "1.0.0",
     *     "mode": "test"
     *   },
     *   "timestamp": "2026-10-11T09:00:00Z"
     * }
     *
     * SECURITY: Do not add DB connection details, credentials, or internal
     * config values to this response. Keep it minimal.
     */
    @GetMapping("/health")
    @Operation(summary = "Health check", description = "Returns application status and version")
    public ResponseEntity<ApiResponse<Map<String, String>>> health() {
        return ResponseEntity.ok(
                ApiResponse.success(Map.of(
                        "status", "UP",
                        "version", appVersion
                ))
        );
    }
}
