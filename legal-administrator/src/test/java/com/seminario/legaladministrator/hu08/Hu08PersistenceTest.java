package com.seminario.legaladministrator.hu08;

import com.seminario.legaladministrator.modules.payments.*;
import com.seminario.legaladministrator.modules.processes.LegalProcessEntity;
import com.seminario.legaladministrator.modules.processes.repository.LegalProcessRepository;
import com.seminario.legaladministrator.modules.users.repository.UserSystemRepository;
import com.seminario.legaladministrator.shared.OperationException;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import static org.assertj.core.api.Assertions.*;

/**
 * El saldo pendiente se calcula sumando abonos: si el mapeo o la suma pierden
 * centavos, el resumen financiero de cada expediente miente.
 * <p>
 * Requiere PostgreSQL desechable, sin servidor HTTP ni contenedores.
 * Nunca utiliza la conexión de .env.
 */
@EnabledIfSystemProperty(named = "hu08.integration", matches = "true")
@DataJpaTest(showSql = false, properties = {
        "spring.config.import=", "spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.open-in-view=false", "spring.flyway.baseline-on-migrate=false"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class Hu08PersistenceTest {
    private static final ZoneId GUATEMALA = ZoneId.of("America/Guatemala");
    private static final LocalDate TODAY = LocalDate.now(GUATEMALA);
    /** Abogada sembrada por V2; es quien registra abonos. */
    private static final String ABOGADA = "3002234560901";

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

    @Test
    void amountsSurviveTheRoundTripWithTheirExactValue() {
        long caseId = newCase(null);
        payment(caseId, "1500.50", PaymentType.ANTICIPO, TODAY.minusDays(3));
        payment(caseId, "250.25", PaymentType.PAGO_FINAL, TODAY);

        entityManager.flush();
        entityManager.clear();

        var stored = payments.findByLegalProcessIdOrderByPaymentDateDescIdDesc(caseId);
        assertThat(stored).hasSize(2);
        assertThat(stored).extracting(CasePaymentEntity::getAmount)
                .containsExactly(new BigDecimal("250.25"), new BigDecimal("1500.50"));
        assertThat(stored).extracting(CasePaymentEntity::getAmount)
                .allSatisfy(amount -> assertThat(amount.scale()).isEqualTo(CasePaymentEntity.AMOUNT_SCALE));
    }

    @Test
    void activePaymentsKeepTheirExactValueWhileAnnulledOnesStayVisible() {
        long caseId = newCase(new BigDecimal("10000.00"));
        payment(caseId, "0.10", PaymentType.ANTICIPO, TODAY.minusDays(30));
        payment(caseId, "0.20", PaymentType.ABONO, TODAY.minusDays(20));
        payment(caseId, "1000.00", PaymentType.PAGO_FINAL, TODAY.minusDays(1));
        entityManager.flush();

        var annulled = payment(caseId, "500.00", PaymentType.ABONO, TODAY);
        annulled.setActive(false);
        annulled.setAnnulledAt(java.time.Instant.now());
        annulled.setAnnulledBy(ABOGADA);
        annulled.setAnnulReason("Corrección de registro de prueba");
        entityManager.flush();
        entityManager.clear();

        var visible = payments.findByLegalProcessIdOrderByPaymentDateDescIdDesc(caseId);
        assertThat(visible).hasSize(4);
        assertThat(visible).filteredOn(CasePaymentEntity::isActive)
                .extracting(CasePaymentEntity::getAmount)
                .containsExactly(new BigDecimal("1000.00"), new BigDecimal("0.20"), new BigDecimal("0.10"));
        assertThat(visible).filteredOn(entity -> !entity.isActive())
                .extracting(CasePaymentEntity::getAmount)
                .containsExactly(new BigDecimal("500.00"));
        assertThat(cases.findById(caseId).orElseThrow().getTotalAmount())
                .isEqualByComparingTo("10000.00");
    }

    @Test
    void aCaseWithoutPaymentsHasNoRowsToSummarize() {
        long caseId = newCase(null);
        assertThat(payments.findByLegalProcessIdOrderByPaymentDateDescIdDesc(caseId)).isEmpty();
        assertThat(payments.findByLegalProcessIdAndActiveTrueOrderByPaymentDateDescIdDesc(caseId)).isEmpty();
    }

    @Test
    void listingShowsOnlyActivePaymentsNewestFirst() {
        long caseId = newCase(null);
        payment(caseId, "100.00", PaymentType.ANTICIPO, TODAY.minusDays(10));
        payment(caseId, "300.00", PaymentType.ABONO, TODAY);
        payment(caseId, "200.00", PaymentType.ABONO, TODAY);
        var annulled = payment(caseId, "999.00", PaymentType.ABONO, TODAY);
        annulled.setActive(false);
        annulled.setAnnulledAt(java.time.Instant.now());
        annulled.setAnnulledBy(ABOGADA);
        annulled.setAnnulReason("Corrección de registro de prueba");
        entityManager.flush();

        assertThat(payments.findByLegalProcessIdAndActiveTrueOrderByPaymentDateDescIdDesc(caseId))
                .extracting(CasePaymentEntity::getAmount)
                .containsExactlyInAnyOrder(new BigDecimal("100.00"), new BigDecimal("300.00"),
                        new BigDecimal("200.00"));
    }

    @Test
    void anAmountTheColumnWouldRoundIsRejectedInsteadOfStored() {
        long caseId = newCase(null);
        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            payment(caseId, "10.005", PaymentType.ABONO, TODAY);
            payments.flush();
        }))
                .isInstanceOf(OperationException.class)
                .hasMessageContaining("2 decimales");

        assertThat(payments.findByLegalProcessIdOrderByPaymentDateDescIdDesc(caseId)).isEmpty();
    }

    @Test
    void paymentsAreNotReachableFromAnotherCase() {
        long first = newCase(null);
        long second = newCase(null);
        var stored = payment(first, "100.00", PaymentType.ANTICIPO, TODAY);
        entityManager.flush();

        assertThat(payments.findByIdAndLegalProcessId(stored.getId(), second)).isEmpty();
        assertThat(payments.findByIdAndLegalProcessId(stored.getId(), first)).isPresent();
    }

    @Test
    void anUnagreedTotalStaysNullInsteadOfBecomingZero() {
        long unagreed = newCase(null);
        long agreed = newCase(new BigDecimal("7500.00"));
        entityManager.flush();
        entityManager.clear();

        assertThat(cases.findById(unagreed).orElseThrow().getTotalAmount()).isNull();
        assertThat(cases.findById(agreed).orElseThrow().getTotalAmount())
                .isEqualByComparingTo("7500.00");
        LegalProcessEntity entity = cases.findById(agreed).orElseThrow();
        assertThat(entity.getTotalAmount().scale()).isEqualTo(CasePaymentEntity.AMOUNT_SCALE);
    }

    /** Crea un expediente con JDBC y devuelve su id. */
    private long newCase(BigDecimal total) {
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
                    process_type_version_snapshot, total_amount)
                values(?, '3002234560901', ?, 'OPEN', ?, ?, 'Financiero', 0, ?)
                returning id
                """, Long.class, dpi, templateId, TODAY.minusYears(1),
                "EXP-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(), total);
    }

    private CasePaymentEntity payment(long caseId, String amount, PaymentType type, LocalDate date) {
        var payment = new CasePaymentEntity();
        payment.setId(UUID.randomUUID());
        payment.setLegalProcess(cases.getReferenceById(caseId));
        payment.setRegisteredBy(users.getReferenceById(ABOGADA));
        payment.setAmount(new BigDecimal(amount));
        payment.setPaymentType(type);
        payment.setPaymentMethod(PaymentMethod.TRANSFERENCIA);
        payment.setConcept(type == PaymentType.ANTICIPO ? "50% inicial" : "Abono");
        payment.setPaymentDate(date);
        return payments.saveAndFlush(payment);
    }
}
