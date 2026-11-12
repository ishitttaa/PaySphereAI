package com.paysphere.settlement.controller;

import com.paysphere.common.response.ApiResponse;
import com.paysphere.settlement.entity.Settlement;
import com.paysphere.settlement.service.SettlementService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/v1/settlements")
@RequiredArgsConstructor
@Tag(name = "Settlements", description = "Razorpay bank settlement tracking")
public class SettlementController {

    private final SettlementService settlementService;

    public record RecordSettlementRequest(
            @NotBlank(message = "Razorpay settlement ID is required")
            String razorpaySettlementId,
            @Positive(message = "Amount in paise must be positive")
            long amountPaise,
            long feesPaise,
            long taxPaise,
            String utr
    ) {}

    @Operation(summary = "List all settlements, paginated (newest first)")
    @GetMapping
    public ResponseEntity<ApiResponse<Page<Settlement>>> listSettlements(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        Pageable pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        Page<Settlement> settlements = settlementService.listSettlements(pageable);
        return ResponseEntity.ok(ApiResponse.success(settlements));
    }

    @Operation(summary = "Record a settlement from Razorpay")
    @PostMapping("/record")
    public ResponseEntity<ApiResponse<Settlement>> recordSettlement(
            @Valid @RequestBody RecordSettlementRequest request
    ) {
        Settlement settlement = settlementService.recordSettlement(
                request.razorpaySettlementId(),
                request.amountPaise(),
                request.feesPaise(),
                request.taxPaise(),
                request.utr()
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(settlement));
    }
}
