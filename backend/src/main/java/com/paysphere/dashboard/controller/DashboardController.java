package com.paysphere.dashboard.controller;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.paysphere.common.response.ApiResponse;
import com.paysphere.ledger.repository.LedgerTransactionRepository;
import com.paysphere.order.entity.BusinessOrderStatus;
import com.paysphere.order.repository.BusinessOrderRepository;
import com.paysphere.payment.entity.PaymentStatus;
import com.paysphere.payment.repository.PaymentRepository;
import com.paysphere.refund.repository.RefundRepository;
import com.paysphere.risk.entity.RiskFlagStatus;
import com.paysphere.risk.repository.RiskFlagRepository;
import com.paysphere.security.MerchantScope;
import com.paysphere.settlement.repository.SettlementRepository;
import com.paysphere.webhook.repository.WebhookEventRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/v1/dashboard")
@RequiredArgsConstructor
@Tag(name = "Dashboard", description = "Aggregated platform metrics and KPIs")
public class DashboardController {

    private final PaymentRepository paymentRepository;
    private final BusinessOrderRepository orderRepository;
    private final RefundRepository refundRepository;
    private final SettlementRepository settlementRepository;
    private final RiskFlagRepository riskFlagRepository;
    private final WebhookEventRepository webhookEventRepository;
    private final LedgerTransactionRepository ledgerTransactionRepository;

    /**
     * The last four fields are platform-wide (settlements, risk flags, webhooks and the ledger have no
     * merchant link yet). They are null, and left out of the JSON, for a merchant caller.
     */
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DashboardStatsResponse(
            long grossRevenuePaise,
            long totalRevenuePaise,          // NET: gross minus processed refunds
            double totalRevenueRupees,       // display only, derived from net
            long successfulPaymentsCount,
            long failedPaymentsCount,
            long totalOrdersCount,
            long pendingOrdersCount,
            long refundedAmountPaise,
            long refundsCount,
            Long settledAmountPaise,
            Long openRiskFlagsCount,
            Long webhookEventsCount,
            Long ledgerTransactionsCount
    ) {}

    private static long nz(Long value) {
        return value == null ? 0L : value;
    }

    @Operation(summary = "Get aggregated dashboard KPIs (all sums computed in the database)")
    @GetMapping("/stats")
    public ResponseEntity<ApiResponse<DashboardStatsResponse>> getStats() {
        Optional<UUID> scope = MerchantScope.current();
        DashboardStatsResponse stats = scope.isPresent()
                ? merchantStats(scope.get())
                : platformStats();
        return ResponseEntity.ok(ApiResponse.success(stats));
    }

    /** Platform staff: every merchant together, plus the platform-wide figures. */
    private DashboardStatsResponse platformStats() {
        long gross = nz(paymentRepository.sumCapturedAmountPaise());
        long refunded = nz(refundRepository.sumProcessedAmountPaise());
        long net = gross - refunded;

        long successPayments = paymentRepository.countByStatus(PaymentStatus.CAPTURED)
                + paymentRepository.countByStatus(PaymentStatus.REFUNDED);
        long failedPayments = paymentRepository.countByStatus(PaymentStatus.FAILED);

        long pendingOrders = orderRepository.countByStatus(BusinessOrderStatus.PENDING)
                + orderRepository.countByStatus(BusinessOrderStatus.PAYMENT_INITIATED);

        return DashboardStatsResponse.builder()
                .grossRevenuePaise(gross)
                .totalRevenuePaise(net)
                .totalRevenueRupees(net / 100.0)
                .successfulPaymentsCount(successPayments)
                .failedPaymentsCount(failedPayments)
                .totalOrdersCount(orderRepository.count())
                .pendingOrdersCount(pendingOrders)
                .refundedAmountPaise(refunded)
                .refundsCount(refundRepository.count())
                .settledAmountPaise(nz(settlementRepository.sumAmountPaise()))
                .openRiskFlagsCount(riskFlagRepository.countByStatus(RiskFlagStatus.OPEN))
                .webhookEventsCount(webhookEventRepository.count())
                .ledgerTransactionsCount(ledgerTransactionRepository.count())
                .build();
    }

    /** A merchant caller: only that merchant's orders, payments and refunds. Sums stay in the database. */
    private DashboardStatsResponse merchantStats(UUID merchantId) {
        long gross = nz(paymentRepository.sumCapturedAmountPaiseByMerchant(merchantId));
        long refunded = nz(refundRepository.sumProcessedAmountPaiseByMerchant(merchantId));
        long net = gross - refunded;

        long successPayments =
                paymentRepository.countByStatusAndBusinessOrderMerchantId(PaymentStatus.CAPTURED, merchantId)
                + paymentRepository.countByStatusAndBusinessOrderMerchantId(PaymentStatus.REFUNDED, merchantId);
        long failedPayments =
                paymentRepository.countByStatusAndBusinessOrderMerchantId(PaymentStatus.FAILED, merchantId);

        long pendingOrders =
                orderRepository.countByMerchantIdAndStatus(merchantId, BusinessOrderStatus.PENDING)
                + orderRepository.countByMerchantIdAndStatus(merchantId, BusinessOrderStatus.PAYMENT_INITIATED);

        return DashboardStatsResponse.builder()
                .grossRevenuePaise(gross)
                .totalRevenuePaise(net)
                .totalRevenueRupees(net / 100.0)
                .successfulPaymentsCount(successPayments)
                .failedPaymentsCount(failedPayments)
                .totalOrdersCount(orderRepository.countByMerchantId(merchantId))
                .pendingOrdersCount(pendingOrders)
                .refundedAmountPaise(refunded)
                .refundsCount(refundRepository.countByBusinessOrderMerchantId(merchantId))
                .build();   // platform-wide fields stay null
    }
}