package com.paysphere.risk.controller;

import com.paysphere.common.response.ApiResponse;
import com.paysphere.risk.entity.RiskFlag;
import com.paysphere.risk.service.RiskService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import com.paysphere.security.CurrentUser;

import java.util.List;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/v1/risk-flags")
@RequiredArgsConstructor
@Tag(name = "Risk & Fraud", description = "Deterministic risk rule flags and review")
public class RiskController {

    private final RiskService riskService;

    public record DismissFlagRequest(String notes, UUID reviewerUserId) {}

    @Operation(summary = "List open risk flags requiring review (paginated, newest first)")
    @GetMapping
    public ResponseEntity<ApiResponse<Page<RiskFlag>>> getOpenFlags(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        Pageable pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        Page<RiskFlag> flags = riskService.getOpenFlags(pageable);
        return ResponseEntity.ok(ApiResponse.success(flags));
    }

    @Operation(summary = "Get risk flags for a specific entity (Payment or BusinessOrder)")
    @GetMapping("/entity/{entityType}/{entityId}")
    public ResponseEntity<ApiResponse<List<RiskFlag>>> getFlagsForEntity(
            @PathVariable String entityType,
            @PathVariable UUID entityId
    ) {
        List<RiskFlag> flags = riskService.getFlagsForEntity(entityType, entityId);
        return ResponseEntity.ok(ApiResponse.success(flags));
    }
 // reviewerUserId is ignored: a caller must not be able to name someone else as the reviewer.
    @Operation(summary = "Dismiss / resolve a risk flag after admin review")
    @PostMapping("/{flagId}/dismiss")
    public ResponseEntity<ApiResponse<Void>> dismissFlag(
            @PathVariable UUID flagId,
            @RequestBody(required = false) DismissFlagRequest request
    ) {
        UUID reviewer = CurrentUser.id().orElse(null);   // from the login token; the body's reviewerUserId is ignored
        String notes = request != null && request.notes() != null ? request.notes() : "Dismissed by admin";
        riskService.dismissFlag(flagId, reviewer, notes);
        return ResponseEntity.ok(ApiResponse.success(null));
    }
}
