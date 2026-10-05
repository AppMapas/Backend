package com.seminario.legaladministrator.hu08;

import com.seminario.legaladministrator.config.security.OfficeAccess;
import com.seminario.legaladministrator.modules.payments.*;
import com.seminario.legaladministrator.modules.payments.PaymentDtos.*;
import com.seminario.legaladministrator.modules.processes.LegalProcessEntity;
import com.seminario.legaladministrator.modules.processes.repository.LegalProcessRepository;
import com.seminario.legaladministrator.modules.processes.service.CaseRequestGuard;
import com.seminario.legaladministrator.modules.users.repository.UserSystemRepository;
import com.seminario.legaladministrator.shared.OperationException;
import jakarta.persistence.EntityManager;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import static org.assertj.core.api.Assertions.*;

/**
 * El criterio de aceptación de la HU, de punta a punta contra PostgreSQL real:
 * abonos registrados por el servicio, saldo pendiente derivado sin columnas que
 * puedan desincronizarse, y dinero que no pierde centavos por el camino.
 * <p>
 * Requiere PostgreSQL desechable. Nunca utiliza la conexión de .env.
 */
@EnabledIfSystemProperty(named = "hu08.integration", matches = "true")
@DataJpaTest(showSql = false, properties = {
        "spring.config.import=", "spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.open-in-view=false", "spring.flyway.baseline-on-migrate=false"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({CasePaymentService.class, OfficeAccess.class, CaseRequestGuard.class,
        Hu08LedgerTest.Configuration.class})
class Hu08LedgerTest {
    private static final ZoneId GUATEMALA = ZoneId.of("America/Guatemala");
    private static final LocalDate TODAY = LocalDate.now(GUATEMALA);
    /** Abogada sembrada por V2. */
    private static final String ABOGADA = "abogada@system.com";

    @TestConfiguration
    @EnableMethodSecurity
    static class Configuration {
        @Bean(destroyMethod = "close")
        ValidatorFactory validatorFactory() {
            return Validation.buildDefaultValidatorFactory();
        }
        @Bean
        Validator validator(ValidatorFactory factory) {
            return factory.getValidator();
        }
    }

    @Autowired CasePaymentService service;
    @Autowired CasePaymentRepository payments;
    @Autowired LegalProcessRepository cases;
    @Autowired UserSystemRepository users;
    @Autowired EntityManager entityManager;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        String url = System.getProperty("hu08.test.url", "");
        if (!url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/hu08_test")) {
            throw new IllegalArgumentException(
                    "Usa una base local desechable llamada hu08_test con puerto explícito.");
        }
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", () -> System.getProperty("hu08.test.user", "michael"));
        registry.add("spring.datasource.password", () -> "");
    }

    @BeforeEach
    void authenticate() {
        authenticate(ABOGADA, "Abogada");
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void pendingBalanceIsDerivedFromTheStoredPaymentsDownToTheCent() {
        long caseId = newCase();
        agreeTotal(caseId, "10000.00");

        register(caseId, "5000.00", PaymentType.ANTICIPO, "50% inicial", TODAY.minusDays(10));
        register(caseId, "0.10", PaymentType.ABONO, "Ajuste", TODAY.minusDays(5));
        register(caseId, "0.20", PaymentType.ABONO, "Ajuste", TODAY);
        entityManager.clear();

        var ledger = service.ledger(caseId);
        // Con double, 5000.00 + 0.10 + 0.20 no daría 4999.70 exacto.
        assertThat(ledger.paidAmount()).isEqualTo(new BigDecimal("5000.30"));
        assertThat(ledger.advancesAmount()).isEqualTo(new BigDecimal("5000.00"));
        assertThat(ledger.pendingAmount()).isEqualTo(new BigDecimal("4999.70"));
        assertThat(ledger.settled()).isFalse();
        assertThat(ledger.totalAgreed()).isTrue();

        // Nada de esto vive en una columna: se deriva de las tres filas leídas.
        assertThat(jdbc.queryForObject(
                "select total_amount::text from legal_process where id = ?", String.class, caseId))
                .isEqualTo("10000.00");
        assertThat(countPayments(caseId)).isEqualTo(3);
    }

    @Test
    void settlingTheBalanceExactlyIsRecognized() {
        long caseId = newCase();
        agreeTotal(caseId, "10000.00");
        register(caseId, "4000.00", PaymentType.ANTICIPO, "Anticipo", TODAY.minusDays(30));
        register(caseId, "6000.00", PaymentType.PAGO_FINAL, "Liquidación", TODAY);

        var ledger = service.ledger(caseId);
        assertThat(ledger.pendingAmount()).isEqualTo(new BigDecimal("0.00"));
        assertThat(ledger.settled()).isTrue();
    }

    @Test
    void payingMoreThanAgreedLeavesABalanceInFavor() {
        long caseId = newCase();
        agreeTotal(caseId, "1000.00");
        register(caseId, "1500.00", PaymentType.ABONO, "Sobrepago", TODAY);

        var ledger = service.ledger(caseId);
        assertThat(ledger.pendingAmount()).isEqualTo(new BigDecimal("-500.00"));
        assertThat(ledger.overpaid()).isTrue();
        assertThat(ledger.settled()).isFalse();
    }

    @Test
    void withoutAnAgreedTotalThereIsNoPendingBalanceToShow() {
        long caseId = newCase();
        register(caseId, "2500.00", PaymentType.ANTICIPO, "Anticipo", TODAY);

        var ledger = service.ledger(caseId);
        assertThat(ledger.totalAmount()).isNull();
        assertThat(ledger.totalAgreed()).isFalse();
        assertThat(ledger.pendingAmount()).isNull();
        assertThat(ledger.paidAmount()).isEqualTo(new BigDecimal("2500.00"));
    }

    @Test
    void anAmountTypedWithOneDecimalLandsStoredWithTwo() {
        long caseId = newCase();
        agreeTotal(caseId, "3000.00");

        register(caseId, "1500.5", PaymentType.ANTICIPO, "50% inicial", TODAY);
        entityManager.clear();

        // El cliente móvil puede mandar 1500.5; la columna lo guarda como 1500.50.
        assertThat(jdbc.queryForObject(
                "select amount::text from case_payment where legal_process_id = ?", String.class, caseId))
                .isEqualTo("1500.50");
        var ledger = service.ledger(caseId);
        assertThat(ledger.paidAmount()).isEqualTo(new BigDecimal("1500.50"));
        assertThat(ledger.pendingAmount()).isEqualTo(new BigDecimal("1499.50"));
    }

    @Test
    void aRepeatedKeyDoesNotChargeTheClientTwice() {
        long caseId = newCase();
        agreeTotal(caseId, "10000.00");
        var request = new CreatePaymentRequest(UUID.randomUUID(), new BigDecimal("5000.00"),
                PaymentType.ANTICIPO, PaymentMethod.TRANSFERENCIA, "50% inicial", TODAY, null);

        var first = service.register(caseId, request);
        var retry = service.register(caseId, request);
        entityManager.clear();

        assertThat(first.replayed()).isFalse();
        assertThat(retry.replayed()).isTrue();
        assertThat(countPayments(caseId)).isEqualTo(1);
        assertThat(service.ledger(caseId).pendingAmount()).isEqualTo(new BigDecimal("5000.00"));
    }

    @Test
    void reusingAKeyWithAnotherAmountIsRefused() {
        long caseId = newCase();
        UUID key = UUID.randomUUID();
        service.register(caseId, new CreatePaymentRequest(key, new BigDecimal("5000.00"),
                PaymentType.ANTICIPO, PaymentMethod.TRANSFERENCIA, "50% inicial", TODAY, null));

        assertThatThrownBy(() -> service.register(caseId, new CreatePaymentRequest(key,
                new BigDecimal("500.00"), PaymentType.ANTICIPO, PaymentMethod.TRANSFERENCIA,
                "50% inicial", TODAY, null)))
                .isInstanceOf(OperationException.class)
                .hasMessageContaining("clave");
        assertThat(countPayments(caseId)).isEqualTo(1);
    }

    @Test
    void evenIfTheGuardFailedTheDatabaseRefusesADuplicatedKey() {
        long caseId = newCase();
        UUID key = UUID.randomUUID();
        service.register(caseId, new CreatePaymentRequest(key, new BigDecimal("100.00"),
                PaymentType.ABONO, PaymentMethod.EFECTIVO, "Primer abono", TODAY, null));
        entityManager.flush();
        assertThat(countPayments(caseId)).isEqualTo(1);

        // Cinturón de seguridad: la restricción única impide el doble cobro aunque
        // la aplicación se cuelgue entre la comprobación y la inserción. Va al final
        // porque la violación aborta la transacción y deja inutilizable lo que siga.
        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            var entity = new CasePaymentEntity();
            entity.setId(UUID.randomUUID());
            entity.setLegalProcess(cases.getReferenceById(caseId));
            entity.setRegisteredBy(users.getReferenceById("3002234560901"));
            entity.setAmount(new BigDecimal("100.00"));
            entity.setPaymentType(PaymentType.ABONO);
            entity.setPaymentMethod(PaymentMethod.EFECTIVO);
            entity.setConcept("Intento de doble cobro");
            entity.setPaymentDate(TODAY);
            entity.setRequestId(key);
            entity.setRequestHash("otra huella");
            payments.saveAndFlush(entity);
        })).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void annullingAPaymentReturnsItsMoneyToThePendingBalance() {
        long caseId = newCase();
        agreeTotal(caseId, "10000.00");
        register(caseId, "5000.00", PaymentType.ANTICIPO, "50% inicial", TODAY.minusDays(1));
        var mistake = register(caseId, "500.00", PaymentType.ABONO, "Monto equivocado", TODAY);
        entityManager.clear();

        var afterAnnul = service.annul(caseId, mistake.id());
        assertThat(afterAnnul.paidAmount()).isEqualTo(new BigDecimal("5000.00"));
        assertThat(afterAnnul.pendingAmount()).isEqualTo(new BigDecimal("5000.00"));
        // Anulado no es borrado: la fila sigue visible para auditar el error.
        assertThat(afterAnnul.payments()).hasSize(2);
        assertThat(afterAnnul.payments()).filteredOn(payment -> !payment.active()).hasSize(1);

        assertThatThrownBy(() -> service.annul(caseId, mistake.id()))
                .isInstanceOf(OperationException.class)
                .hasMessageContaining("anulado");
    }

    @Test
    void theAgreedTotalCanBeCorrectedAndCleared() {
        long caseId = newCase();
        agreeTotal(caseId, "10000.00");
        register(caseId, "1000.00", PaymentType.ANTICIPO, "Anticipo", TODAY);
        entityManager.clear();

        long version = cases.findById(caseId).orElseThrow().getVersion();
        var corrected = service.setTotalAmount(caseId, new TotalAmountRequest(version, new BigDecimal("8000.00")));
        assertThat(corrected.pendingAmount()).isEqualTo(new BigDecimal("7000.00"));

        var cleared = service.setTotalAmount(caseId,
                new TotalAmountRequest(corrected.caseVersion(), null));
        assertThat(cleared.totalAmount()).isNull();
        assertThat(cleared.pendingAmount()).isNull();
        assertThat(cleared.paidAmount()).isEqualTo(new BigDecimal("1000.00"));

        // El saldo pendiente jamás quedó guardado en el expediente.
        assertThat(jdbc.queryForObject(
                "select total_amount::text from legal_process where id = ?", String.class, caseId)).isNull();
    }

    @Test
    void aStaleVersionDoesNotOverwriteTheTotal() {
        long caseId = newCase();
        agreeTotal(caseId, "10000.00");
        entityManager.clear();

        assertThatThrownBy(() -> service.setTotalAmount(caseId,
                new TotalAmountRequest(99L, new BigDecimal("1.00"))))
                .isInstanceOf(OperationException.class)
                .hasMessageContaining("Recarga");
    }

    @Test
    void aSecretaryCannotReadOrChangeTheMoneyOfACase() {
        long caseId = newCase();
        agreeTotal(caseId, "10000.00");
        entityManager.clear();
        authenticate("secretaria@system.com", "Secretaria");

        assertThatThrownBy(() -> service.ledger(caseId)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.register(caseId, new CreatePaymentRequest(UUID.randomUUID(),
                new BigDecimal("100.00"), PaymentType.ABONO, PaymentMethod.EFECTIVO, "Abono", TODAY, null)))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.annul(caseId, UUID.randomUUID()))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.setTotalAmount(caseId, new TotalAmountRequest(1L, BigDecimal.ONE)))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(countPayments(caseId)).isZero();
    }

    @Test
    void anInactiveCaseRefusesNewPayments() {
        long caseId = newCase();
        agreeTotal(caseId, "10000.00");
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            LegalProcessEntity entity = cases.findById(caseId).orElseThrow();
            entity.setActive(false);
            cases.saveAndFlush(entity);
        });

        assertThatThrownBy(() -> service.register(caseId, new CreatePaymentRequest(UUID.randomUUID(),
                new BigDecimal("100.00"), PaymentType.ABONO, PaymentMethod.EFECTIVO, "Abono", TODAY, null)))
                .isInstanceOf(OperationException.class)
                .hasMessageContaining("inactivo");
    }

    private void agreeTotal(long caseId, String total) {
        long version = cases.findById(caseId).orElseThrow().getVersion();
        service.setTotalAmount(caseId, new TotalAmountRequest(version, new BigDecimal(total)));
        entityManager.clear();
    }

    private PaymentResponse register(long caseId, String amount, PaymentType type, String concept,
                                     LocalDate date) {
        var created = service.register(caseId, new CreatePaymentRequest(UUID.randomUUID(),
                new BigDecimal(amount), type, PaymentMethod.EFECTIVO, concept, date, null));
        return created.ledger().payments().get(0);
    }

    private long countPayments(long caseId) {
        return jdbc.queryForObject("select count(*) from case_payment where legal_process_id = ?",
                Long.class, caseId);
    }

    /** Crea un expediente con JDBC y devuelve su id, igual que en Hu08PersistenceTest. */
    private long newCase() {
        String dpi = "8" + String.format("%012d", ThreadLocalRandom.current().nextLong(1_000_000_000_000L));
        jdbc.update("""
                insert into client_user(dpi, first_name, last_name, email, phone, created_at)
                values(?, 'Cliente', 'Finanzas', ?, '55550002', ?)
                """, dpi, dpi + "@example.test", TODAY.minusYears(1));
        Long templateId = jdbc.queryForObject("""
                insert into process_type(name, status) values(?, 'PUBLISHED')
                returning id
                """, Long.class, "Financiero " + UUID.randomUUID());
        return jdbc.queryForObject("""
                insert into legal_process(dpi_client, id_user_system_assigned, id_process_type,
                    current_status, created_at, case_code, process_type_name_snapshot,
                    process_type_version_snapshot)
                values(?, '3002234560901', ?, 'OPEN', ?, ?, 'Financiero', 0)
                returning id
                """, Long.class, dpi, templateId, TODAY.minusYears(1),
                "EXP-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
    }

    private static void authenticate(String email, String role) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                email, null, List.of(new SimpleGrantedAuthority(role))));
    }
}