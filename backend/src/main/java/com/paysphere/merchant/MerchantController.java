package com.paysphere.merchant;

import com.paysphere.common.response.ApiResponse;
import com.paysphere.merchant.MerchantDtos.CreateMerchantRequest;
import com.paysphere.merchant.MerchantDtos.MerchantResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * POST /api/v1/merchants   create a merchant   (PLATFORM_ADMIN)
 * GET  /api/v1/merchants   list merchants      (PLATFORM_ADMIN, PLATFORM_OPERATOR)
 */
@RestController
@RequestMapping("/api/v1/merchants")
@Tag(name = "Merchants", description = "Merchant onboarding (platform staff only)")
@RequiredArgsConstructor
public class MerchantController {

    private final MerchantService merchantService;

    @PostMapping
    public ResponseEntity<ApiResponse<MerchantResponse>> create(
            @Valid @RequestBody CreateMerchantRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(merchantService.createMerchant(request.name())));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<MerchantResponse>>> list() {
        return ResponseEntity.ok(ApiResponse.success(merchantService.listMerchants()));
    }
}