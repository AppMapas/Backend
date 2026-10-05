package com.seminario.legaladministrator.modules.payments;

import com.seminario.legaladministrator.shared.OperationException;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import static org.assertj.core.api.Assertions.*;

/**
 * El saldo pendiente se deriva sumando estos montos: si el modelo admite un
 * importe que la columna redondea, el resumen financial miente en centavos.
 */
class CasePaymentEntityTest {

    @ParameterizedTest
    @ValueSource(strings = {"0", "0.00", "-1", "-0.01", "10.005", "0.001", "9999999999999.99"})
    void rejectsAmountsThatWouldCorruptTheBalance(String amount) {
        var payment = new CasePaymentEntity();
        payment.setAmount(new BigDecimal(amount));
        assertThatThrownBy(payment::beforeInsert).isInstanceOf(OperationException.class);
    }

    @Test
    void rejectsMissingAmount() {
        assertThatThrownBy(() -> new CasePaymentEntity().beforeInsert())
                .isInstanceOf(OperationException.class).hasMessageContaining("mayor que cero");
    }

    @Test
    void alsoGuardsUpdatesSoEditingCannotBypassTheInvariant() {
        var payment = new CasePaymentEntity();
        payment.setAmount(new BigDecimal("10.5"));
        payment.beforeInsert();
        var previousUpdate = payment.getUpdatedAt();
        payment.setAmount(new BigDecimal("10.555"));
        assertThatThrownBy(payment::beforeUpdate).isInstanceOf(OperationException.class);
        // La entidad inválida ni se toca: updatedAt no avanza si el monto se rechaza.
        assertThat(payment.getUpdatedAt()).isEqualTo(previousUpdate);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.01", "1", "1500.5", "999999999999.99"})
    void acceptsExactAmountsWithinTheColumnCota(String amount) {
        var payment = new CasePaymentEntity();
        payment.setAmount(new BigDecimal(amount));
        assertThatCode(payment::beforeInsert).doesNotThrowAnyException();
        assertThat(payment.getCreatedAt()).isNotNull();
        assertThat(payment.getUpdatedAt()).isEqualTo(payment.getCreatedAt());
    }

    @Test
    void sumOfPaymentsIsExactUnlikeFloatingPoint() {
        var total = Stream.of(new BigDecimal("0.10"), new BigDecimal("0.20"), new BigDecimal("1500.00"))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(total).isEqualByComparingTo("1500.30");
        assertThat(total.scale()).isEqualTo(2);
        // La razón de usar BigDecimal: con double el saldo no cuadra.
        assertThat(0.10 + 0.20).isNotEqualTo(0.30);
    }

    @Test
    void enumValuesMatchTheDatabaseRestrictions() throws IOException {
        var migration = migrationSql();
        // Un enum nuevo sin su literal en la restricción fallaría al guardar en PostgreSQL.
        assertThat(valuesIn(migration, "ck_case_payment_type", "payment_type"))
                .containsExactlyInAnyOrderElementsOf(names(PaymentType.class));
        assertThat(valuesIn(migration, "ck_case_payment_method", "payment_method"))
                .containsExactlyInAnyOrderElementsOf(names(PaymentMethod.class));
    }

    private static Set<String> names(Class<? extends Enum<?>> type) {
        var values = Arrays.stream(type.getEnumConstants()).map(Enum::name).collect(Collectors.toSet());
        assertThat(values).as("%s no debe estar vacío", type.getSimpleName()).isNotEmpty();
        return values;
    }

    private static String migrationSql() throws IOException {
        try (InputStream stream = CasePaymentEntityTest.class.getResourceAsStream(
                "/db/migration/V9__create_case_payments.sql")) {
            assertThat(stream).as("V9__create_case_payments.sql debe estar en el classpath").isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** Extrae los literales de un CHECK del tipo {@code CONSTRAINT x CHECK (columna IN ('A','B'))}. */
    private static Set<String> valuesIn(String migration, String constraint, String column) throws IOException {
        var pattern = Pattern.compile(Pattern.quote(constraint) + "\\s+CHECK\\s*\\(\\s*" + column
                + "\\s+IN\\s*\\(([^)]*)\\)\\)", Pattern.DOTALL);
        Matcher matcher = pattern.matcher(migration);
        assertThat(matcher.find()).as("no se encontró la restricción %s", constraint).isTrue();
        var values = Arrays.stream(matcher.group(1).split(","))
                .map(value -> value.trim().replace("'", ""))
                .collect(Collectors.toSet());
        assertThat(values).as("la restricción %s no debe estar vacía", constraint).isNotEmpty();
        return values;
    }
}