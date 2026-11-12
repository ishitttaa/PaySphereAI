package com.paysphere.audit.controller;

import com.paysphere.audit.entity.AuditLog;
import com.paysphere.audit.service.AuditService;
import com.paysphere.common.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * AuditLog Controller — read-only access to the audit trail.
 */
@RestController
@RequestMapping("/api/v1/audit-logs")
@Tag(name = "Audit Logs", description = "Immutable audit trail of system actions")
@RequiredArgsConstructor
public class AuditLogController {

    private final AuditService auditService;

    @GetMapping
    @Operation(summary = "List all audit logs")
    public ResponseEntity<ApiResponse<Page<AuditLog>>> listLogs(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return ResponseEntity.ok(ApiResponse.success(
                auditService.getAllLogs(PageRequest.of(page, Math.min(size, 200)))));
    }

    @GetMapping("/entity/{entityType}/{entityId}")
    @Operation(summary = "Get audit logs for a specific entity")
    public ResponseEntity<ApiResponse<Page<AuditLog>>> getLogsForEntity(
            @PathVariable String entityType,
            @PathVariable UUID entityId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return ResponseEntity.ok(ApiResponse.success(
                auditService.getLogsForEntity(entityType, entityId,
                        PageRequest.of(page, Math.min(size, 200)))));
    }
}
