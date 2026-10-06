package com.seminario.legaladministrator.modules.cash;

import com.seminario.legaladministrator.config.security.OfficeAccess;
import com.seminario.legaladministrator.modules.payments.*;
import com.seminario.legaladministrator.modules.payments.PaymentDtos.*;
import com.seminario.legaladministrator.modules.processes.service.CaseRequestGuard;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CashIncomeSummaryTest {
    CasePaymentService payments;
    CashService cash;

    @BeforeEach void prepare() {
        payments = mock(CasePaymentService.class);
        cash = new CashService(mock(ExpenseRecordRepository.class), payments, mock(CasePaymentRepository.class),
                mock(OfficeAccess.class), mock(CaseRequestGuard.class), mock(NamedParameterJdbcTemplate.class),
                mock(Validator.class));
    }

    @Test void displaysTheAgreedCostAndOnlyTheActuallyCollectedAmount() {
        when(payments.ledger(1L)).thenReturn(new Ledger(new BigDecimal("2500.00"), new BigDecimal("0.30"),
                new BigDecimal("0.30"), new BigDecimal("2499.70"), true, false, false, 4L, true, List.of()));
        var result = cash.incomeSummary(1L);
        assertThat(result.totalAmount()).isEqualTo("2500.00");
        assertThat(result.paidAmount()).isEqualTo("0.30");
        assertThat(result.pendingAmount()).isEqualTo("2499.70");
        assertThat(result.finalPaymentCount()).isZero();
        assertThat(result.lastFinalPaymentDate()).isNull();
        verify(payments).ledger(1L);
        verifyNoMoreInteractions(payments);
    }

    @Test void finalPaymentSummaryExcludesAnnulledPaymentsAndUsesTheLatestPaymentDate() {
        when(payments.ledger(1L)).thenReturn(new Ledger(new BigDecimal("2500.00"), new BigDecimal("2500.00"),
                BigDecimal.ZERO, new BigDecimal("0.00"), true, true, false, 4L, true, List.of(
                payment("999.00", PaymentType.PAGO_FINAL, false, "2026-10-05"),
                payment("0.20", PaymentType.PAGO_FINAL, true, "2026-10-02"),
                payment("2499.70", PaymentType.ABONO, true, "2026-10-03"),
                payment("0.10", PaymentType.PAGO_FINAL, true, "2026-10-01"))));
        var result = cash.incomeSummary(1L);
        assertThat(result.finalPaymentAmount()).isEqualTo("0.30");
        assertThat(result.finalPaymentCount()).isEqualTo(2);
        assertThat(result.lastFinalPaymentDate()).isEqualTo(LocalDate.of(2026, 10, 2));
        assertThat(result.settled()).isTrue();
        assertThat(result.pendingAmount()).isEqualTo("0.00");
    }

    @Test void anUnknownAgreedPriceStaysUnknownEvenWhenAFinalPaymentExists() {
        when(payments.ledger(1L)).thenReturn(new Ledger(null, new BigDecimal("50.00"), BigDecimal.ZERO,
                null, false, false, false, 1L, true,
                List.of(payment("50.00", PaymentType.PAGO_FINAL, true, "2026-10-01"))));
        var result = cash.incomeSummary(1L);
        assertThat(result.totalAmount()).isNull();
        assertThat(result.pendingAmount()).isNull();
        assertThat(result.totalAgreed()).isFalse();
        assertThat(result.finalPaymentAmount()).isEqualTo("50.00");
    }

    @Test void preservesAmountsBeyondTheExactJavascriptNumberRangeAndCreditBalances() {
        when(payments.ledger(1L)).thenReturn(new Ledger(new BigDecimal("999999999999.99"),
                new BigDecimal("9007199254740993.01"), BigDecimal.ZERO, new BigDecimal("-9006199254740993.02"),
                true, false, true, 3L, false, List.of()));
        var result = cash.incomeSummary(1L);
        assertThat(result.paidAmount()).isEqualTo("9007199254740993.01");
        assertThat(result.pendingAmount()).isEqualTo("-9006199254740993.02");
        assertThat(result.overpaid()).isTrue();
        assertThat(result.caseActive()).isFalse();
    }

    private PaymentResponse payment(String amount, PaymentType type, boolean active, String date) {
        return new PaymentResponse(UUID.randomUUID(), new BigDecimal(amount), type, PaymentMethod.EFECTIVO,
                "Cobro", LocalDate.parse(date), null, active, "3002234560901", Instant.parse("2026-10-01T14:00:00Z"));
    }
}
