package com.paysphere.reconciliation;

import com.paysphere.common.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

@RestController
@RequestMapping("/api/v1/reconciliation")
@RequiredArgsConstructor
public class ReconciliationController {

    private final ReconciliationService service;

    @PostMapping("/run")
    public ResponseEntity<ApiResponse<ReconciliationReport>> run(Authentication authentication) {
        String actor = authentication != null ? authentication.getName() : "API";
        return ResponseEntity.ok(ApiResponse.success(service.run(Instant.now(), actor)));
    }
}