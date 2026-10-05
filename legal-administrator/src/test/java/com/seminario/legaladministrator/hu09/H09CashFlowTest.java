package com.seminario.legaladministrator.hu09;

import com.seminario.legaladministrator.config.security.OfficeAccess;
import com.seminario.legaladministrator.modules.cash.*;
import com.seminario.legaladministrator.modules.cash.CashDtos.*;
import com.seminario.legaladministrator.modules.payments.*;
import com.seminario.legaladministrator.modules.processes.service.CaseRequestGuard;
import com.seminario.legaladministrator.shared.OperationException;
import jakarta.persistence.EntityManager;
import jakarta.validation.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.*;
import org.springframework.transaction.annotation.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

/** PostgreSQL desechable: nunca utiliza .env ni inicia un servidor HTTP. */
@EnabledIfSystemProperty(named="h09.integration", matches="true")
@DataJpaTest(showSql=false, properties={"spring.config.import=", "spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.open-in-view=false", "spring.flyway.baseline-on-migrate=false"})
@AutoConfigureTestDatabase(replace=AutoConfigureTestDatabase.Replace.NONE)
@Import({CashService.class, CasePaymentService.class, OfficeAccess.class, CaseRequestGuard.class, H09CashFlowTest.Config.class})
class H09CashFlowTest {
    static final LocalDate TODAY = LocalDate.now(ZoneId.of("America/Guatemala"));
    @TestConfiguration @EnableMethodSecurity
    static class Config {
        @Bean(destroyMethod="close") ValidatorFactory factory() { return Validation.buildDefaultValidatorFactory(); }
        @Bean Validator validator(ValidatorFactory factory) { return factory.getValidator(); }
        @Bean NamedParameterJdbcTemplate namedJdbc(JdbcTemplate jdbc) { return new NamedParameterJdbcTemplate(jdbc); }
    }
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        String url = System.getProperty("h09.test.url", "");
        if (!url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/h09_test"))
            throw new IllegalArgumentException("Usa una base desechable h09_test con puerto explícito.");
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", () -> System.getProperty("h09.test.user", "krm"));
        registry.add("spring.datasource.password", () -> "");
    }
    @Autowired CashService cash;
    @Autowired CasePaymentService payments;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager em;
    String token;
    @BeforeEach void prepare() { token = UUID.randomUUID().toString(); authenticate("abogada", "Abogada"); }
    @AfterEach void clear() { SecurityContextHolder.clearContext(); }
    static void authenticate(String name, String role) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                name + "@system.com", "", List.of(new SimpleGrantedAuthority(role))));
    }
    Overview overview(String status) { return cash.overview(TODAY, TODAY, null, null, status, token, null, 0, 12); }
    ExpenseRequest expense(UUID key, ExpenseCategory category, String amount) {
        return new ExpenseRequest(key, category, new BigDecimal(amount), token + " gasto", TODAY, PaymentMethod.EFECTIVO, null);
    }
    IncomeRequest income(UUID key, long caseId, String amount) {
        return new IncomeRequest(key, caseId, new BigDecimal(amount), token + " cobro", TODAY,
                PaymentType.ANTICIPO, PaymentMethod.TRANSFERENCIA, null);
    }

    @Test void realCollectionsAndExpensesBalanceExactlyWithoutDuplicatingIncome() {
        long caseId = newCase();
        payments.setTotalAmount(caseId, new PaymentDtos.TotalAmountRequest(0L, new BigDecimal("5000.00")));
        cash.registerIncome(income(UUID.randomUUID(), caseId, "0.10"));
        cash.registerIncome(income(UUID.randomUUID(), caseId, "0.20"));
        cash.registerExpense(expense(UUID.randomUUID(), ExpenseCategory.UTILES_OFICINA, "0.05"));
        cash.registerExpense(expense(UUID.randomUUID(), ExpenseCategory.GASTOS_PERSONALES, "0.02"));
        em.clear();
        assertThat(overview("ACTIVE").summary()).isEqualTo(new Summary("0.30", "0.05", "0.02", "0.25", "0.23", "GTQ"));
        assertThat(overview("ACTIVE").movements().totalElements()).isEqualTo(4);
        assertThat(payments.ledger(caseId).pendingAmount()).isEqualByComparingTo("4999.70");
        assertThat(jdbc.queryForObject("select count(*) from income_record", Long.class)).isZero();
    }

    @Test void personalExpensesArePrivateEvenFromAnAdministrator() {
        var privateExpense = cash.registerExpense(expense(UUID.randomUUID(), ExpenseCategory.GASTOS_PERSONALES, "80.00"));
        cash.registerExpense(expense(UUID.randomUUID(), ExpenseCategory.UTILES_OFICINA, "20.00"));
        em.clear();
        authenticate("admin", "Administrador");
        assertThat(overview("ALL").summary().personalExpenses()).isEqualTo("0.00");
        assertThat(overview("ALL").movements().totalElements()).isEqualTo(1);
        assertThatThrownBy(() -> cash.getExpense(Long.valueOf(privateExpense.sourceId())))
                .isInstanceOf(OperationException.class).hasMessage("Movimiento no encontrado.");
        assertThatThrownBy(() -> cash.annulExpense(Long.valueOf(privateExpense.sourceId()), new AnnulRequest(0L, "Corrección")))
                .isInstanceOf(OperationException.class).hasMessage("Movimiento no encontrado.");
    }

    @Test void retriesAreIdempotentAndCannotChangeTheAmountOrActor() {
        var request = expense(UUID.randomUUID(), ExpenseCategory.UTILES_OFICINA, "50.00");
        var saved = cash.registerExpense(request);
        assertThat(cash.registerExpense(request).replayed()).isTrue();
        assertThatThrownBy(() -> cash.registerExpense(expense(request.requestId(), request.category(), "51.00")))
                .isInstanceOf(OperationException.class).hasMessageContaining("otros datos");
        authenticate("admin", "Administrador");
        assertThatThrownBy(() -> cash.registerExpense(request)).isInstanceOf(OperationException.class);
        assertThat(saved.sourceId()).isNotBlank();
        assertThat(overview("ALL").movements().totalElements()).isEqualTo(1);
    }

    @Test void theSameRequestKeyCannotCrossFromExpenseToEitherPaymentEndpoint() {
        var request = expense(UUID.randomUUID(), ExpenseCategory.UTILES_OFICINA, "10.00");
        cash.registerExpense(request);
        long caseId = newCase();
        assertThatThrownBy(() -> cash.registerIncome(income(request.requestId(), caseId, "10.00")))
                .isInstanceOf(OperationException.class).hasMessageContaining("gasto");
        assertThatThrownBy(() -> payments.register(caseId, new PaymentDtos.CreatePaymentRequest(request.requestId(),
                BigDecimal.TEN, PaymentType.ABONO, PaymentMethod.EFECTIVO, "Cobro", TODAY, null)))
                .isInstanceOf(OperationException.class).hasMessageContaining("gasto");
        assertThat(payments.ledger(caseId).paidAmount()).isEqualByComparingTo("0");
    }

    @Test void annullingRetainsAuditAndRetriesButRemovesTheAmountFromTheBalance() {
        var saved = cash.registerExpense(expense(UUID.randomUUID(), ExpenseCategory.UTILES_OFICINA, "20.00"));
        long id = Long.parseLong(saved.sourceId());
        assertThatThrownBy(() -> cash.annulExpense(id, new AnnulRequest(9L, "Error")))
                .isInstanceOf(OperationException.class).hasMessageContaining("Recarga");
        var annulled = cash.annulExpense(id, new AnnulRequest(0L, "Registro equivocado"));
        assertThat(annulled.active()).isFalse();
        assertThat(annulled.annulledBy()).isEqualTo("3002234560901");
        assertThat(annulled.annulledAt()).isNotNull();
        assertThat(cash.annulExpense(id, new AnnulRequest(0L, "Registro equivocado")).active()).isFalse();
        assertThat(overview("ALL").summary().officeExpenses()).isEqualTo("0.00");
        assertThat(overview("ALL").movements().totalElements()).isEqualTo(1);
    }

    @Test void annullingIncomeUpdatesBothCashAndTheExistingCaseLedger() {
        long caseId = newCase();
        var request = income(UUID.randomUUID(), caseId, "50.00");
        var saved = cash.registerIncome(request);
        assertThat(cash.registerIncome(request).replayed()).isTrue();
        UUID paymentId = UUID.fromString(saved.sourceId());
        cash.annulIncome(caseId, paymentId, new AnnulRequest(saved.caseVersion(), "Duplicado manual"));
        jdbc.update("update legal_process set active=false where id=?", caseId);
        em.clear();
        cash.annulIncome(caseId, paymentId, new AnnulRequest(saved.caseVersion(), "Duplicado manual"));
        assertThat(cash.registerIncome(request).replayed()).isTrue();
        assertThat(overview("ALL").summary().income()).isEqualTo("0.00");
        assertThat(payments.ledger(caseId).payments()).hasSize(1);
        assertThat(payments.ledger(caseId).paidAmount()).isEqualByComparingTo("0");
    }

    @Test void paginationFiltersAndLiteralSearchMatchTheirTotals() {
        for (int i=0; i<3; i++) cash.registerExpense(expense(UUID.randomUUID(), ExpenseCategory.UTILES_OFICINA, "1.00"));
        var result = cash.overview(TODAY, TODAY, "UTILES_OFICINA", PaymentMethod.EFECTIVO, "ACTIVE", token, null, 1, 2);
        assertThat(result.summary().officeExpenses()).isEqualTo("3.00");
        assertThat(result.movements().content()).hasSize(1);
        assertThat(result.movements().totalPages()).isEqualTo(2);
        assertThat(cash.overview(TODAY, TODAY, null, null, "ALL", "%_' OR 1=1 --", null, 0, 12).movements().content()).isEmpty();
        assertThatThrownBy(() -> cash.overview(TODAY, TODAY.minusDays(1), null, null, "ALL", token, null, 0, 12))
                .isInstanceOf(OperationException.class);
        assertThatThrownBy(() -> cash.overview(TODAY, TODAY, null, null, "ALL", token, null, 0, 101))
                .isInstanceOf(OperationException.class);
    }

    @Test void invalidMoneyFutureDatesAndNonOfficeRolesAreRejected() {
        for (String amount : List.of("0", "-1", "10.005", "1000000000000"))
            assertThatThrownBy(() -> cash.registerExpense(expense(UUID.randomUUID(), ExpenseCategory.UTILES_OFICINA, amount)))
                    .isInstanceOf(OperationException.class);
        var future = new ExpenseRequest(UUID.randomUUID(), ExpenseCategory.UTILES_OFICINA, BigDecimal.ONE,
                token, TODAY.plusDays(1), PaymentMethod.EFECTIVO, null);
        assertThatThrownBy(() -> cash.registerExpense(future)).isInstanceOf(OperationException.class).hasMessageContaining("futura");
        authenticate("secretaria", "Secretaria");
        assertThatThrownBy(() -> overview("ALL")).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> cash.registerExpense(future)).isInstanceOf(AccessDeniedException.class);
    }

    @Test @Transactional(propagation=Propagation.NOT_SUPPORTED)
    void simultaneousRetriesProduceOneCommittedExpense() throws Exception {
        var request = expense(UUID.randomUUID(), ExpenseCategory.UTILES_OFICINA, "0.10");
        var start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        Callable<Mutation> call = () -> {
            authenticate("abogada", "Abogada");
            try { start.await(); return cash.registerExpense(request); }
            finally { SecurityContextHolder.clearContext(); }
        };
        try {
            var first = executor.submit(call);
            var second = executor.submit(call);
            start.countDown();
            var a = first.get(15, TimeUnit.SECONDS);
            var b = second.get(15, TimeUnit.SECONDS);
            assertThat(a.sourceId()).isEqualTo(b.sourceId());
            assertThat(List.of(a.replayed(), b.replayed())).containsExactlyInAnyOrder(true, false);
            assertThat(overview("ACTIVE").summary().officeExpenses()).isEqualTo("0.10");
        } finally { executor.shutdownNow(); }
    }

    private long newCase() {
        String dpi = "8" + String.format("%012d", ThreadLocalRandom.current().nextLong(1_000_000_000_000L));
        jdbc.update("insert into client_user(dpi,first_name,last_name,email,phone,created_at) values(?,'Cliente','Caja',?,'55550002',?)",
                dpi, dpi + "@example.test", TODAY.minusYears(1));
        Long type = jdbc.queryForObject("insert into process_type(name,status) values(?,'PUBLISHED') returning id", Long.class, "Caja " + token);
        return jdbc.queryForObject("""
                insert into legal_process(dpi_client,id_user_system_assigned,id_process_type,current_status,created_at,
                    case_code,process_type_name_snapshot,process_type_version_snapshot)
                values(?,'3002234560901',?,'OPEN',?,?,'Caja',0) returning id
                """, Long.class, dpi, type, TODAY.minusYears(1), "EXP-" + UUID.randomUUID());
    }
}
