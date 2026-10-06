package com.seminario.legaladministrator.modules.payments;

import com.seminario.legaladministrator.config.security.OfficeAccess;
import com.seminario.legaladministrator.modules.payments.PaymentDtos.*;
import com.seminario.legaladministrator.modules.processes.LegalProcessEntity;
import com.seminario.legaladministrator.modules.processes.repository.LegalProcessRepository;
import com.seminario.legaladministrator.modules.processes.service.CaseRequestGuard;
import com.seminario.legaladministrator.modules.users.UserSystemEntity;
import com.seminario.legaladministrator.shared.OperationException;
import jakarta.validation.Validation;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * El resumen financiero es la promesa de la HU: lo abonado menos lo pactado.
 * Aquí se comprueba, hasta los centavos, que no se pierde ni se inventa nada.
 */
class CasePaymentServiceTest {
    private static final LocalDate TODAY = LocalDate.now();
    private static final String ABOGADA = "3002234560901";

    CasePaymentRepository payments = mock(CasePaymentRepository.class);
    LegalProcessRepository cases = mock(LegalProcessRepository.class);
    OfficeAccess officeAccess = mock(OfficeAccess.class);
    CaseRequestGuard requestGuard = new CaseRequestGuard(mock(JdbcTemplate.class));
    CasePaymentService service = new CasePaymentService(payments, cases, officeAccess, requestGuard,
            Validation.buildDefaultValidatorFactory().getValidator());

    LegalProcessEntity legalCase = new LegalProcessEntity();
    List<CasePaymentEntity> stored = new ArrayList<>();

    @BeforeEach
    void prepare() {
        legalCase.setId(1L);
        legalCase.setCaseCode("EXP-1");
        legalCase.setVersion(7L);
        legalCase.setTotalAmount(new BigDecimal("10000.00"));
        when(officeAccess.current()).thenReturn(operator(ABOGADA));
        when(cases.findById(1L)).thenReturn(Optional.of(legalCase));
        when(cases.findForUpdate(1L)).thenReturn(Optional.of(legalCase));
        when(payments.findByLegalProcessIdOrderByPaymentDateDescIdDesc(1L))
                .thenAnswer(call -> new ArrayList<>(stored));
        when(payments.findByRequestId(any())).thenReturn(Optional.empty());
        when(payments.saveAndFlush(any())).thenAnswer(call -> {
            var entity = call.getArgument(0, CasePaymentEntity.class);
            entity.beforeInsert();
            // Un saveAndFlush de una entidad ya guardada la actualiza, no la duplica.
            if (!stored.contains(entity)) {
                stored.add(entity);
            }
            return entity;
        });
    }

    @Test
    void pendingBalanceIsTotalMinusPaymentsAndCountsAdvancesSeparately() {
        advance("5000.00", PaymentType.ANTICIPO);
        advance("1500.25", PaymentType.ABONO);
        advance("0.30", PaymentType.ABONO);

        var ledger = service.ledger(1L);

        assertThat(ledger.paidAmount()).isEqualByComparingTo("6500.55");
        // Los anticipos se informan aparte: el 50% inicial se distingue del resto.
        assertThat(ledger.advancesAmount()).isEqualByComparingTo("5000.00");
        assertThat(ledger.pendingAmount()).isEqualByComparingTo("3499.45");
        assertThat(ledger.totalAgreed()).isTrue();
        assertThat(ledger.settled()).isFalse();
        assertThat(ledger.overpaid()).isFalse();
    }

    @Test
    void amountsKeepTwoDecimalsSoTheFrontendComparisonIsStable() {
        advance("100", PaymentType.ABONO);
        advance("0.1", PaymentType.ABONO);

        var ledger = service.ledger(1L);
        // Sin setScale, la suma devolvería 100.10 pero un abono aislado 0.1.
        assertThat(ledger.paidAmount().scale()).isEqualTo(CasePaymentEntity.AMOUNT_SCALE);
        assertThat(ledger.paidAmount()).isEqualTo(new BigDecimal("100.10"));
        assertThat(ledger.pendingAmount().scale()).isEqualTo(CasePaymentEntity.AMOUNT_SCALE);
    }

    @Test
    void exactSettlementIsReportedAsSettled() {
        advance("10000.00", PaymentType.PAGO_FINAL);

        var ledger = service.ledger(1L);
        assertThat(ledger.pendingAmount()).isEqualByComparingTo("0.00");
        assertThat(ledger.settled()).isTrue();
        assertThat(ledger.overpaid()).isFalse();
    }

    @Test
    void payingMoreThanAgreedShowsASaldoInFavorInsteadOfHidingIt() {
        advance("12000.00", PaymentType.ABONO);

        var ledger = service.ledger(1L);
        // Un sobrepago real no se bloquea, pero tampoco se disimula.
        assertThat(ledger.pendingAmount()).isEqualByComparingTo("-2000.00");
        assertThat(ledger.overpaid()).isTrue();
        assertThat(ledger.settled()).isFalse();
    }

    @Test
    void withoutAnAgreedTotalThePendingBalanceIsUnknownNotZero() {
        legalCase.setTotalAmount(null);
        advance("5000.00", PaymentType.ANTICIPO);

        var ledger = service.ledger(1L);
        assertThat(ledger.totalAgreed()).isFalse();
        assertThat(ledger.pendingAmount()).isNull();
        assertThat(ledger.paidAmount()).isEqualByComparingTo("5000.00");
    }

    @Test
    void annulledPaymentsStopCountingButRemainListed() {
        advance("5000.00", PaymentType.ANTICIPO);
        var mistake = advance("500.00", PaymentType.ABONO);
        when(payments.findByIdAndLegalProcessId(mistake.getId(), 1L)).thenReturn(Optional.of(mistake));

        var ledger = service.annul(1L, mistake.getId());

        assertThat(ledger.paidAmount()).isEqualByComparingTo("5000.00");
        assertThat(ledger.pendingAmount()).isEqualByComparingTo("5000.00");
        assertThat(ledger.payments()).hasSize(2);
        assertThat(ledger.payments()).filteredOn(PaymentResponse::active).hasSize(1);
    }

    @Test
    void annullingTwiceReportsThatNothingChanged() {
        advance("500.00", PaymentType.ABONO);
        var payment = stored.get(0);
        payment.setActive(false);
        when(payments.findByIdAndLegalProcessId(payment.getId(), 1L)).thenReturn(Optional.of(payment));

        assertThatThrownBy(() -> service.annul(1L, payment.getId()))
                .isInstanceOfSatisfying(OperationException.class,
                        error -> assertThat(error.getStatus().value()).isEqualTo(409));
    }

    @Test
    void aPaymentFromAnotherCaseIsNotFound() {
        assertThatThrownBy(() -> service.annul(1L, UUID.randomUUID()))
                .isInstanceOfSatisfying(OperationException.class,
                        error -> assertThat(error.getStatus().value()).isEqualTo(404));
    }

    @Test
    void registeringTwiceWithTheSameKeyReplaysInsteadOfDuplicating() {
        var request = request("1500.50", PaymentType.ANTICIPO);

        var first = service.register(1L, request);
        assertThat(first.replayed()).isFalse();
        assertThat(first.ledger().paidAmount()).isEqualByComparingTo("1500.50");

        when(payments.findByRequestId(request.requestId())).thenReturn(Optional.of(stored.get(0)));
        var second = service.register(1L, request);

        assertThat(second.replayed()).isTrue();
        assertThat(second.ledger().paidAmount()).isEqualByComparingTo("1500.50");
        assertThat(stored).hasSize(1);
    }

    @Test
    void reusingAKeyWithAnotherAmountIsRefused() {
        var request = request("1500.50", PaymentType.ANTICIPO);
        service.register(1L, request);
        when(payments.findByRequestId(request.requestId())).thenReturn(Optional.of(stored.get(0)));

        assertThatThrownBy(() -> service.register(1L,
                request(request.requestId(), "9000.00", PaymentType.ANTICIPO)))
                .isInstanceOfSatisfying(OperationException.class,
                        error -> assertThat(error.getStatus().value()).isEqualTo(409));
        assertThat(stored).hasSize(1);
    }

    @Test
    void retryingWithTheSameAmountWrittenDifferentlyStillReplays() {
        var request = request("1500.5", PaymentType.ANTICIPO);
        service.register(1L, request);
        when(payments.findByRequestId(request.requestId())).thenReturn(Optional.of(stored.get(0)));

        // 1500.5 y 1500.50 son el mismo abono: la huella se normaliza a dos decimales.
        var replay = service.register(1L, request(request.requestId(), "1500.50", PaymentType.ANTICIPO));
        assertThat(replay.replayed()).isTrue();
        assertThat(stored).hasSize(1);
    }

    @Test
    void anotherOperatorCannotReuseSomeoneElsesKey() {
        var request = request("1500.50", PaymentType.ANTICIPO);
        service.register(1L, request);
        when(payments.findByRequestId(request.requestId())).thenReturn(Optional.of(stored.get(0)));
        when(officeAccess.current()).thenReturn(operator("3003345670301"));

        assertThatThrownBy(() -> service.register(1L, request))
                .isInstanceOfSatisfying(OperationException.class,
                        error -> assertThat(error.getStatus().value()).isEqualTo(409));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "0.00", "-5.00", "10.005"})
    void rejectsAmountsThatWouldRoundOrLowerTheBalance(String amount) {
        assertThatThrownBy(() -> service.register(1L, request(amount, PaymentType.ABONO)))
                .isInstanceOfSatisfying(OperationException.class,
                        error -> assertThat(error.getStatus().value()).isEqualTo(400));
        verify(payments, never()).saveAndFlush(any());
    }

    @Test
    void rejectsBlankConceptAndFutureDate() {
        assertThatThrownBy(() -> service.register(1L, new CreatePaymentRequest(UUID.randomUUID(),
                new BigDecimal("100.00"), PaymentType.ABONO, PaymentMethod.EFECTIVO, "   ", TODAY, null)))
                .isInstanceOfSatisfying(OperationException.class,
                        error -> assertThat(error.getStatus().value()).isEqualTo(400));
        assertThatThrownBy(() -> service.register(1L, new CreatePaymentRequest(UUID.randomUUID(),
                new BigDecimal("100.00"), PaymentType.ABONO, PaymentMethod.EFECTIVO, "Abono",
                TODAY.plusDays(1), null)))
                .isInstanceOfSatisfying(OperationException.class,
                        error -> assertThat(error.getStatus().value()).isEqualTo(400));
        verify(payments, never()).saveAndFlush(any());
    }

    @Test
    void blankReferenceIsStoredAsAbsentRatherThanAsBlankText() {
        var created = service.register(1L, new CreatePaymentRequest(UUID.randomUUID(),
                new BigDecimal("100.00"), PaymentType.ABONO, PaymentMethod.EFECTIVO, "Abono", TODAY, "   "));

        assertThat(created.ledger().payments().get(0).reference()).isNull();
    }

    @Test
    void inactiveCaseRejectsEveryWriteButStillAllowsReading() {
        legalCase.setActive(false);
        assertThat(service.ledger(1L).caseActive()).isFalse();

        assertThatThrownBy(() -> service.register(1L, request("100.00", PaymentType.ABONO)))
                .isInstanceOfSatisfying(OperationException.class,
                        error -> assertThat(error.getStatus().value()).isEqualTo(409));
        assertThatThrownBy(() -> service.setTotalAmount(1L, new TotalAmountRequest(7L, new BigDecimal("1.00"))))
                .isInstanceOfSatisfying(OperationException.class,
                        error -> assertThat(error.getStatus().value()).isEqualTo(409));
        verify(payments, never()).saveAndFlush(any());
    }

    @Test
    void unknownCaseIsNotFound() {
        when(cases.findById(99L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.ledger(99L))
                .isInstanceOfSatisfying(OperationException.class,
                        error -> assertThat(error.getStatus().value()).isEqualTo(404));
    }

    @Test
    void settingTheTotalRecalculatesThePendingBalance() {
        var updated = service.setTotalAmount(1L, new TotalAmountRequest(7L, new BigDecimal("25000.00")));
        // Todavía no hay abonos: el pendiente es el total completo.
        assertThat(updated.totalAmount()).isEqualByComparingTo("25000.00");
        assertThat(updated.pendingAmount()).isEqualByComparingTo("25000.00");

        advance("5000.00", PaymentType.ANTICIPO);

        assertThat(service.ledger(1L).pendingAmount()).isEqualByComparingTo("20000.00");
    }

    @Test
    void theTotalCanBeClearedBackToNotAgreed() {
        var ledger = service.setTotalAmount(1L, new TotalAmountRequest(7L, null));

        assertThat(ledger.totalAmount()).isNull();
        assertThat(ledger.totalAgreed()).isFalse();
        assertThat(ledger.pendingAmount()).isNull();
    }

    @Test
    void aStaleVersionDoesNotOverwriteSomeoneElsesTotal() {
        assertThatThrownBy(() -> service.setTotalAmount(1L, new TotalAmountRequest(3L, new BigDecimal("1.00"))))
                .isInstanceOfSatisfying(OperationException.class,
                        error -> assertThat(error.getStatus().value()).isEqualTo(409));
        verify(cases, never()).saveAndFlush(any());
    }

    @Test
    void theLedgerCarriesTheCaseVersionTheFrontendNeedsToUpdateTheTotal() {
        assertThat(service.ledger(1L).caseVersion()).isEqualTo(7L);
    }

    private CreatePaymentRequest request(String amount, PaymentType type) {
        return request(UUID.randomUUID(), amount, type);
    }

    private CreatePaymentRequest request(UUID requestId, String amount, PaymentType type) {
        return new CreatePaymentRequest(requestId, new BigDecimal(amount), type,
                PaymentMethod.TRANSFERENCIA, "50% inicial", TODAY, null);
    }

    private UserSystemEntity operator(String dpi) {
        var operator = new UserSystemEntity();
        operator.setDpi(dpi);
        return operator;
    }

    /** Abono ya persistido, como lo dejaría una llamada previa al servicio. */
    private CasePaymentEntity advance(String amount, PaymentType type) {
        var entity = new CasePaymentEntity();
        entity.setId(UUID.randomUUID());
        entity.setLegalProcess(legalCase);
        entity.setAmount(new BigDecimal(amount));
        entity.setPaymentType(type);
        entity.setPaymentMethod(PaymentMethod.EFECTIVO);
        entity.setConcept("Abono");
        entity.setPaymentDate(TODAY);
        entity.setRegisteredBy(operator(ABOGADA));
        entity.beforeInsert();
        stored.add(entity);
        return entity;
    }
}
