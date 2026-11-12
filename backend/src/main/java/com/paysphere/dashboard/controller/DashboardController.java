package com.paysphere.dashboard.controller;

import com.paysphere.common.response.ApiResponse;
import com.paysphere.ledger.repository.LedgerTransactionRepository;
import com.paysphere.order.entity.BusinessOrderStatus;
import com.paysphere.order.repository.BusinessOrderRepository;
import com.paysphere.payment.entity.Payment;
import com.paysphere.payment.entity.PaymentStatus;
import com.paysphere.payment.repository.PaymentRepository;
import com.paysphere.refund.entity.Refund;
import com.paysphere.refund.entity.RefundStatus;
import com.paysphere.refund.repository.RefundRepository;
import com.paysphere.risk.entity.RiskFlagStatus;
import com.paysphere.risk.repository.RiskFlagRepository;
import com.paysphere.settlement.entity.Settlement;
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

import java.util.List;

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

    @Builder
    public record DashboardStatsResponse(
            long totalRevenuePaise,
            double totalRevenueRupees,
            long successfulPaymentsCount,
            long failedPaymentsCount,
            long totalOrdersCount,
            long pendingOrdersCount,
            long refundedAmountPaise,
            long refundsCount,
            long settledAmountPaise,
            long openRiskFlagsCount,
            long webhookEventsCount,
            long ledgerTransactionsCount
    ) {}

    @Operation(summary = "Get aggregated real-time dashboard KPIs and counts")
    @GetMapping("/stats")
    public ResponseEntity<ApiResponse<DashboardStatsResponse>> getStats() {
        List<Payment> allPayments = paymentRepository.findAll();
        long grossRevenue = allPayments.stream()
                .filter(p -> p.getStatus() == PaymentStatus.CAPTURED)
                .mapToLong(p -> p.getAmountPaise() != null ? p.getAmountPaise() : 0L)
                .sum();

        long successPayments = paymentRepository.countByStatus(PaymentStatus.CAPTURED);
        long failedPayments = paymentRepository.countByStatus(PaymentStatus.FAILED);

        long totalOrders = orderRepository.count();
        long pendingOrders = orderRepository.countByStatus(BusinessOrderStatus.PENDING)
                + orderRepository.countByStatus(BusinessOrderStatus.PAYMENT_INITIATED);

        List<Refund> allRefunds = refundRepository.findAll();
        long refundedPaise = allRefunds.stream()
                .filter(r -> r.getStatus() == RefundStatus.PROCESSED)
                .mapToLong(r -> r.getAmountPaise() != null ? r.getAmountPaise() : 0L)
                .sum();

        List<Settlement> allSettlements = settlementRepository.findAll();
        long settledPaise = allSettlements.stream()
                .mapToLong(s -> s.getAmountPaise() != null ? s.getAmountPaise() : 0L)
                .sum();

        long openRisk = riskFlagRepository.countByStatus(RiskFlagStatus.OPEN);
        long webhookEvents = webhookEventRepository.count();
        long ledgerTxs = ledgerTransactionRepository.count();

        DashboardStatsResponse stats = DashboardStatsResponse.builder()
                .totalRevenuePaise(grossRevenue)
                .totalRevenueRupees(grossRevenue / 100.0)
                .successfulPaymentsCount(successPayments)
                .failedPaymentsCount(failedPayments)
                .totalOrdersCount(totalOrders)
                .pendingOrdersCount(pendingOrders)
                .refundedAmountPaise(refundedPaise)
                .refundsCount(allRefunds.size())
                .settledAmountPaise(settledPaise)
                .openRiskFlagsCount(openRisk)
                .webhookEventsCount(webhookEvents)
                .ledgerTransactionsCount(ledgerTxs)
                .build();

        return ResponseEntity.ok(ApiResponse.success(stats));
    }
}
