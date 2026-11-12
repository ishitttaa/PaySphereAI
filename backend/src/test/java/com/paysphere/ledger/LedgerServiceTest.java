package com.paysphere.ledger;

import com.paysphere.ledger.entity.LedgerAccount;
import com.paysphere.ledger.entity.LedgerAccountType;
import com.paysphere.ledger.entity.LedgerTransaction;
import com.paysphere.ledger.repository.LedgerAccountRepository;
import com.paysphere.ledger.repository.LedgerTransactionRepository;
import com.paysphere.ledger.service.LedgerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LedgerServiceTest {

    @Mock
    private LedgerTransactionRepository txRepo;

    @Mock
    private LedgerAccountRepository accountRepo;

    @InjectMocks
    private LedgerService ledgerService;

    private LedgerAccount receivable;
    private LedgerAccount revenue;
    private LedgerAccount merchantBank;
    private LedgerAccount fees;
    private LedgerAccount refundsPayable;

    @BeforeEach
    void setUp() {
        receivable = LedgerAccount.builder().id(UUID.randomUUID()).code("RAZORPAY_RECEIVABLE").accountType(LedgerAccountType.ASSET).name("Receivable").build();
        revenue = LedgerAccount.builder().id(UUID.randomUUID()).code("REVENUE").accountType(LedgerAccountType.REVENUE).name("Revenue").build();
        merchantBank = LedgerAccount.builder().id(UUID.randomUUID()).code("MERCHANT_BANK").accountType(LedgerAccountType.ASSET).name("Bank").build();
        fees = LedgerAccount.builder().id(UUID.randomUUID()).code("RAZORPAY_FEES").accountType(LedgerAccountType.EXPENSE).name("Fees").build();
        refundsPayable = LedgerAccount.builder().id(UUID.randomUUID()).code("REFUNDS_PAYABLE").accountType(LedgerAccountType.LIABILITY).name("Refunds").build();
    }

    @Test
    @DisplayName("postPaymentCaptured posts balanced debit and credit entries")
    void testPostPaymentCapturedSuccess() {
        UUID paymentId = UUID.randomUUID();
        when(txRepo.existsByIdempotencyKey("PAYMENT-" + paymentId)).thenReturn(false);
        when(accountRepo.findByCode("RAZORPAY_RECEIVABLE")).thenReturn(Optional.of(receivable));
        when(accountRepo.findByCode("REVENUE")).thenReturn(Optional.of(revenue));

        ledgerService.postPaymentCaptured(paymentId, 10000L, "INR");

        ArgumentCaptor<LedgerTransaction> captor = ArgumentCaptor.forClass(LedgerTransaction.class);
        verify(txRepo).save(captor.capture());

        LedgerTransaction savedTx = captor.getValue();
        assertThat(savedTx.getAmountPaise()).isEqualTo(10000L);
        assertThat(savedTx.getEntries()).hasSize(2);

        long debits = savedTx.getEntries().stream()
                .filter(e -> e.getEntryType() == com.paysphere.ledger.entity.LedgerEntry.EntryType.DEBIT)
                .mapToLong(com.paysphere.ledger.entity.LedgerEntry::getAmountPaise).sum();
        long credits = savedTx.getEntries().stream()
                .filter(e -> e.getEntryType() == com.paysphere.ledger.entity.LedgerEntry.EntryType.CREDIT)
                .mapToLong(com.paysphere.ledger.entity.LedgerEntry::getAmountPaise).sum();

        assertThat(debits).isEqualTo(credits);
        assertThat(debits).isEqualTo(10000L);
    }

    @Test
    @DisplayName("postPaymentCaptured is idempotent and skips if already posted")
    void testPostPaymentCapturedIdempotent() {
        UUID paymentId = UUID.randomUUID();
        when(txRepo.existsByIdempotencyKey("PAYMENT-" + paymentId)).thenReturn(true);

        ledgerService.postPaymentCaptured(paymentId, 10000L, "INR");

        verify(txRepo, never()).save(any());
        verify(accountRepo, never()).findByCode(any());
    }

    @Test
    @DisplayName("postSettlement correctly debits bank and fees, and credits receivable")
    void testPostSettlementSuccess() {
        UUID settlementId = UUID.randomUUID();
        when(txRepo.existsByIdempotencyKey("SETTLEMENT-" + settlementId)).thenReturn(false);
        when(accountRepo.findByCode("MERCHANT_BANK")).thenReturn(Optional.of(merchantBank));
        when(accountRepo.findByCode("RAZORPAY_FEES")).thenReturn(Optional.of(fees));
        when(accountRepo.findByCode("RAZORPAY_RECEIVABLE")).thenReturn(Optional.of(receivable));

        ledgerService.postSettlement(settlementId, 10000L, 200L, "INR");

        ArgumentCaptor<LedgerTransaction> captor = ArgumentCaptor.forClass(LedgerTransaction.class);
        verify(txRepo).save(captor.capture());

        LedgerTransaction savedTx = captor.getValue();
        assertThat(savedTx.getAmountPaise()).isEqualTo(10000L);
        assertThat(savedTx.getEntries()).hasSize(3);

        long debits = savedTx.getEntries().stream()
                .filter(e -> e.getEntryType() == com.paysphere.ledger.entity.LedgerEntry.EntryType.DEBIT)
                .mapToLong(com.paysphere.ledger.entity.LedgerEntry::getAmountPaise).sum();
        long credits = savedTx.getEntries().stream()
                .filter(e -> e.getEntryType() == com.paysphere.ledger.entity.LedgerEntry.EntryType.CREDIT)
                .mapToLong(com.paysphere.ledger.entity.LedgerEntry::getAmountPaise).sum();

        assertThat(debits).isEqualTo(credits);
        assertThat(debits).isEqualTo(10000L);
    }
}
