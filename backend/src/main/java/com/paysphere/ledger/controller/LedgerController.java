package com.paysphere.ledger.controller;

import com.paysphere.common.response.ApiResponse;
import com.paysphere.ledger.entity.LedgerAccount;
import com.paysphere.ledger.entity.LedgerTransaction;
import com.paysphere.ledger.service.LedgerService;
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
import com.paysphere.ledger.dto.LedgerTransactionResponse;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/v1/ledger")
@RequiredArgsConstructor
@Tag(name = "Ledger", description = "Double-entry bookkeeping accounts and journal entries")
public class LedgerController {

    private final LedgerService ledgerService;

    @Operation(summary = "List all chart-of-accounts ledger accounts")
    @GetMapping("/accounts")
    public ResponseEntity<ApiResponse<List<LedgerAccount>>> getAccounts() {
        List<LedgerAccount> accounts = ledgerService.listAccounts();
        return ResponseEntity.ok(ApiResponse.success(accounts));
    }

    @Operation(summary = "List ledger journal transactions with balanced debit/credit entries (paginated, newest first)")
    @GetMapping("/transactions")
    public ResponseEntity<ApiResponse<Page<LedgerTransactionResponse>>> getTransactions(
            @RequestParam(required = false) String sourceType,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        Pageable pageable = PageRequest.of(page, Math.min(size, 100), Sort.by("postedAt").descending());
        Page<LedgerTransactionResponse> transactions;
        if (sourceType != null && !sourceType.isBlank()) {
            transactions = ledgerService.listTransactionsBySourceType(sourceType.toUpperCase(), pageable);
        } else {
            transactions = ledgerService.listTransactions(pageable);
        }
        return ResponseEntity.ok(ApiResponse.success(transactions));
    }
}
