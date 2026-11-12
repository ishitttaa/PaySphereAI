package com.paysphere.settlement.service;

import com.paysphere.ledger.service.LedgerService;
import com.paysphere.settlement.entity.Settlement;
import com.paysphere.settlement.entity.SettlementStatus;
import com.paysphere.settlement.repository.SettlementRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * SettlementService — Records and tracks Razorpay settlement events.
 *
 * When Razorpay settles to merchant bank, we:
 * 1. Record the settlement (idempotent by razorpaySettlementId)
 * 2. Post ledger entries (DR Merchant Bank, DR Fees, CR Receivable)
 *
 * Settlement data arrives via Razorpay webhooks (settlement.processed).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SettlementService {

    private final SettlementRepository settlementRepository;
    private final LedgerService ledgerService;

    /**
     * Record a settlement from Razorpay. Idempotent.
     */
    @Transactional
    public Settlement recordSettlement(String razorpaySettlementId, long amountPaise,
                                        long feesPaise, long taxPaise, String utr) {
        if (settlementRepository.existsByRazorpaySettlementId(razorpaySettlementId)) {
            log.info("Settlement already recorded: {}", razorpaySettlementId);
            return settlementRepository.findByRazorpaySettlementId(razorpaySettlementId).orElseThrow();
        }

        Settlement settlement = Settlement.builder()
                .razorpaySettlementId(razorpaySettlementId)
                .amountPaise(amountPaise)
                .feesPaise(feesPaise)
                .taxPaise(taxPaise)
                .status(SettlementStatus.PROCESSED)
                .utr(utr)
                .settledAt(Instant.now())
                .build();

        settlement = settlementRepository.save(settlement);

        // Post ledger entries
        ledgerService.postSettlement(settlement.getId(), amountPaise, feesPaise, "INR");

        log.info("Settlement recorded: id={}, razorpayId={}, amount={}p, fees={}p, utr={}",
                settlement.getId(), razorpaySettlementId, amountPaise, feesPaise, utr);

        return settlement;
    }

    @Transactional(readOnly = true)
    public Page<Settlement> listSettlements(Pageable pageable) {
        return settlementRepository.findAllByOrderByCreatedAtDesc(pageable);
    }
}
