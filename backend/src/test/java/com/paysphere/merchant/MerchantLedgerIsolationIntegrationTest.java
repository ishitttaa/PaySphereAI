package com.paysphere.merchant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paysphere.BaseIntegrationTest;
import com.paysphere.security.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * N9: a merchant sees only the ledger transactions that come from ITS OWN payments and refunds.
 *  - PAYMENT transactions point at a payment, REFUND and ADJUSTMENT transactions point at a refund;
 *    the merchant is found through the order (no new column, so the append-only ledger is untouched).
 *  - SETTLEMENT transactions have no merchant link, so merchants never see them.
 *  - The chart of accounts (/ledger/accounts) stays platform-only.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = "payment.gateway=simulator")
class MerchantLedgerIsolationIntegrationTest extends BaseIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JwtService jwtService;

    private UUID merchantA;
    private UUID merchantB;
    private UUID aPaymentTx;
    private UUID aRefundTx;
    private UUID aAdjustmentTx;
    private UUID bPaymentTx;
    private UUID bRefundTx;
    private UUID settlementTx;

    @BeforeEach
    void setUp() {
        merchantA = createMerchant("Merchant A ");
        merchantB = createMerchant("Merchant B ");

        UUID orderA = insertOrder(merchantA);
        UUID paymentA = insertPayment(orderA);
        UUID refundA = insertRefund(paymentA, orderA);

        UUID orderB = insertOrder(merchantB);
        UUID paymentB = insertPayment(orderB);
        UUID refundB = insertRefund(paymentB, orderB);

        aPaymentTx = insertLedgerTransaction("PAYMENT", paymentA);
        aRefundTx = insertLedgerTransaction("REFUND", refundA);
        aAdjustmentTx = insertLedgerTransaction("ADJUSTMENT", refundA);
        bPaymentTx = insertLedgerTransaction("PAYMENT", paymentB);
        bRefundTx = insertLedgerTransaction("REFUND", refundB);
        settlementTx = insertLedgerTransaction("SETTLEMENT", UUID.randomUUID());
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private String shortId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private UUID createMerchant(String prefix) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into merchants (id, name) values (?, ?)", id, prefix + id);
        return id;
    }

    private UUID insertOrder(UUID merchantId) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into business_orders (id, merchant_id, order_reference, product_id, "
                + "product_name, amount_paise, status) values (?, ?, ?, 'ISO-PRODUCT', 'Isolation test', 1000, "
                + "'PAYMENT_CAPTURED')", id, merchantId, "ORD-LISO-" + shortId());
        return id;
    }

    private UUID insertPayment(UUID orderId) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into payments (id, business_order_id, razorpay_payment_id, razorpay_order_id, "
                + "status, amount_paise) values (?, ?, ?, ?, 'CAPTURED', 1000)",
                id, orderId, "pay_liso_" + shortId(), "order_liso_" + shortId());
        return id;
    }

    private UUID insertRefund(UUID paymentId, UUID orderId) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into refunds (id, payment_id, business_order_id, amount_paise, status, "
                + "idempotency_key) values (?, ?, ?, 100, 'PROCESSED', ?)",
                id, paymentId, orderId, "liso-" + shortId());
        return id;
    }

    /** A balanced two-line transaction (debit = credit). The ledger is append-only, but INSERT is allowed. */
    private UUID insertLedgerTransaction(String sourceType, UUID sourceId) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into ledger_transactions (id, source_type, source_id, description, "
                + "idempotency_key, amount_paise) values (?, ?, ?, 'Isolation test', ?, 100)",
                id, sourceType, sourceId, "LISO-" + shortId());
        jdbc.update("insert into ledger_entries (id, ledger_transaction_id, ledger_account_id, entry_type, "
                + "amount_paise) values (?, ?, (select id from ledger_accounts where code = 'RAZORPAY_RECEIVABLE'), "
                + "'DEBIT', 100)", UUID.randomUUID(), id);
        jdbc.update("insert into ledger_entries (id, ledger_transaction_id, ledger_account_id, entry_type, "
                + "amount_paise) values (?, ?, (select id from ledger_accounts where code = 'REVENUE'), "
                + "'CREDIT', 100)", UUID.randomUUID(), id);
        return id;
    }

    private String merchantToken(UUID merchantId) {
        return jwtService.createToken(UUID.randomUUID(), "merchant@example.com",
                List.of("MERCHANT_ADMIN"), merchantId);
    }

    private String platformToken() {
        return jwtService.createToken(UUID.randomUUID(), "staff@example.com", List.of("PLATFORM_ADMIN"));
    }

    private MvcResult get(String path, String token) throws Exception {
        return mockMvc.perform(MockMvcRequestBuilders.get(path)
                .header("Authorization", "Bearer " + token)).andReturn();
    }

    private Set<UUID> transactionIdsIn(MvcResult result) throws Exception {
        assertThat(result.getResponse().getStatus())
                .as(result.getResponse().getContentAsString()).isEqualTo(200);
        JsonNode content = objectMapper.readTree(result.getResponse().getContentAsString())
                .get("data").get("content");
        Set<UUID> ids = new HashSet<>();
        content.forEach(node -> ids.add(UUID.fromString(node.get("id").asText())));
        return ids;
    }

    // ── tests ────────────────────────────────────────────────────────────────

    @Test
    void aMerchantSeesOnlyTransactionsFromItsOwnPaymentsAndRefunds() throws Exception {
        Set<UUID> ids = transactionIdsIn(get("/api/v1/ledger/transactions?size=100", merchantToken(merchantA)));

        assertThat(ids).containsExactlyInAnyOrder(aPaymentTx, aRefundTx, aAdjustmentTx);
    }

    @Test
    void anotherMerchantsTransactionsAndSettlementsAreNeverShown() throws Exception {
        Set<UUID> ids = transactionIdsIn(get("/api/v1/ledger/transactions?size=100", merchantToken(merchantA)));

        assertThat(ids).doesNotContain(bPaymentTx, bRefundTx, settlementTx);
    }

    @Test
    void theSourceTypeFilterIsAlsoScoped() throws Exception {
        Set<UUID> payments = transactionIdsIn(
                get("/api/v1/ledger/transactions?sourceType=PAYMENT&size=100", merchantToken(merchantA)));
        Set<UUID> settlements = transactionIdsIn(
                get("/api/v1/ledger/transactions?sourceType=SETTLEMENT&size=100", merchantToken(merchantA)));

        assertThat(payments).containsExactly(aPaymentTx);
        assertThat(settlements).isEmpty();
    }

    @Test
    void aMerchantUserWithoutAMerchantSeesNothing() throws Exception {
        String orphan = jwtService.createToken(UUID.randomUUID(), "orphan@example.com",
                List.of("MERCHANT_ADMIN"));

        assertThat(transactionIdsIn(get("/api/v1/ledger/transactions?size=100", orphan))).isEmpty();
    }

    @Test
    void platformStaffSeeTransactionsOfEveryMerchantAndSettlements() throws Exception {
        Set<UUID> ids = transactionIdsIn(get("/api/v1/ledger/transactions?size=100", platformToken()));

        assertThat(ids).contains(aPaymentTx, aRefundTx, aAdjustmentTx, bPaymentTx, bRefundTx, settlementTx);
    }

    @Test
    void theChartOfAccountsStaysPlatformOnly() throws Exception {
        assertThat(get("/api/v1/ledger/accounts", merchantToken(merchantA))
                .getResponse().getStatus()).isEqualTo(403);
    }
}