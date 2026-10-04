package com.seminario.legaladministrator.hu08;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import static org.assertj.core.api.Assertions.*;

/**
 * Verifica que V9 no altera los expedientes existentes y que el esquema
 * rechaza lo que el modelo promete rechazar.
 * <p>
 * Requiere PostgreSQL desechable: nunca usa la conexión de {@code .env}.
 */
@EnabledIfSystemProperty(named = "hu08.integration", matches = "true")
class Hu08MigrationTest {
    private static String url() {
        String url = System.getProperty("hu08.test.url", "");
        if (!url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/hu08_test")) {
            throw new IllegalArgumentException("Se requiere la base desechable hu08_test.");
        }
        return url;
    }

    private static String user() {
        return System.getProperty("hu08.test.user", "michael");
    }

    @Test
    void upgradeKeepsExistingCasesWithoutInventingATotal() throws Exception {
        String schema = "hu08_upgrade";
        try (Connection connection = DriverManager.getConnection(url(), user(), "");
             var sql = connection.createStatement()) {
            sql.execute("create schema " + schema);
            try {
                migrateTo(8, schema);
                sql.execute("SET search_path TO " + schema);
                sql.execute("""
                        insert into client_user(dpi, first_name, last_name, email, phone, created_at)
                        values('1000000000555', 'Cliente', 'Histórico', 'legacy8@example.test', '55550000', '2021-03-04')
                        """);
                sql.execute("insert into process_type(id, name, status) values(801, 'Trámite anterior', 'PUBLISHED')");
                sql.execute("""
                        insert into legal_process(dpi_client, id_user_system_assigned, id_process_type,
                            current_status, created_at, case_code, process_type_name_snapshot,
                            process_type_version_snapshot)
                        values('1000000000555', '3002234560901', 801, 'Presentado', '2021-03-04',
                            'EXP-801', 'Trámite anterior', 0)
                        """);

                migrateTo(9, schema);

                try (var result = sql.executeQuery("""
                        select case_code, total_amount from legal_process where dpi_client = '1000000000555'
                        """)) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getString("case_code")).isEqualTo("EXP-801");
                    // NULL significa "costo no pactado"; inventar un cero daría un saldo engañoso.
                    assertThat(result.getObject("total_amount")).isNull();
                }
            } finally {
                sql.execute("SET search_path TO public");
                sql.execute("drop schema " + schema + " cascade");
            }
        }
    }

    @Test
    void schemaRejectsAmountsAndDataTheModelForbids() throws Exception {
        String schema = "hu08_constraints";
        try (Connection connection = DriverManager.getConnection(url(), user(), "");
             var sql = connection.createStatement()) {
            sql.execute("create schema " + schema);
            try {
                flyway(schema).load().migrate();
                sql.execute("SET search_path TO " + schema);
                long caseId = legacyCase(sql);

                // Un pago es un ingreso: nunca cero ni negativo.
                assertRejected(sql, caseId, "0.00", "ANTICIPO", "Concepto", 0);
                assertRejected(sql, caseId, "-100.00", "ANTICIPO", "Concepto", 0);
                // El concepto identifica la etapa pagada: no puede ir vacío.
                assertRejected(sql, caseId, "100.00", "ANTICIPO", "   ", 0);
                // Un tipo fuera del catálogo no se persiste.
                assertRejected(sql, caseId, "100.00", "DESCUENTO", "Concepto", 0);
                // No se admiten pagos fechados en el futuro.
                assertRejected(sql, caseId, "100.00", "ANTICIPO", "Concepto", 3);

                // El pago válido de referencia sí entra.
                insert(sql, caseId, "2500.00", "ANTICIPO", "50% inicial", 0);
                try (var result = sql.executeQuery("""
                        select amount, payment_type, payment_method, active
                        from case_payment where legal_process_id = %d
                        """.formatted(caseId))) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getBigDecimal("amount")).isEqualByComparingTo("2500.00");
                    assertThat(result.getString("payment_type")).isEqualTo("ANTICIPO");
                    assertThat(result.getBoolean("active")).isTrue();
                }

                // La clave de idempotencia no puede repetirse.
                insert(sql, caseId, "100.00", "ABONO", "Abono", 0, "11111111-1111-1111-1111-111111111111", "hash-1");
                assertThatThrownBy(() -> insert(sql, caseId, "100.00", "ABONO", "Abono", 0,
                        "11111111-1111-1111-1111-111111111111", "otro-hash")).isInstanceOf(SQLException.class);
                // request_id sin hash no identifica nada.
                assertThatThrownBy(() -> insert(sql, caseId, "100.00", "ABONO", "Abono", 0,
                        "22222222-2222-2222-2222-222222222222", null)).isInstanceOf(SQLException.class);

                // El costo total no puede ser negativo.
                assertThatThrownBy(() -> sql.execute(
                        "update legal_process set total_amount = -1 where id = " + caseId))
                        .isInstanceOf(SQLException.class);
            } finally {
                sql.execute("SET search_path TO public");
                sql.execute("drop schema " + schema + " cascade");
            }
        }
    }

    @Test
    void v9AddsItsOwnTableWithoutTouchingTheLegacyPaymentTables() throws Exception {
        // V1 dejó payment_schedule/income_record sin entidad ni servicio. HU-08 no los
        // reutiliza (guardan montos en double precision) pero tampoco los altera.
        try (Connection connection = DriverManager.getConnection(url(), user(), "");
             var sql = connection.createStatement()) {
            migrateTo(9, null);
            try (var result = sql.executeQuery("""
                    select table_name from information_schema.tables
                    where table_schema = 'public'
                      and table_name in ('case_payment', 'payment_schedule', 'income_record')
                    order by table_name
                    """)) {
                var found = new ArrayList<String>();
                while (result.next()) {
                    found.add(result.getString(1));
                }
                assertThat(found).containsExactly("case_payment", "income_record", "payment_schedule");
            }
            // El monto nuevo es exacto; el heredado es de coma flotante.
            try (var result = sql.executeQuery("""
                    select table_name, data_type from information_schema.columns
                    where table_schema = 'public'
                      and (table_name, column_name) in (('case_payment', 'amount'),
                                                         ('payment_schedule', 'amount'))
                    order by table_name
                    """)) {
                assertThat(result.next()).isTrue();
                assertThat(result.getString("table_name")).isEqualTo("case_payment");
                assertThat(result.getString("data_type")).isEqualTo("numeric");
                assertThat(result.next()).isTrue();
                assertThat(result.getString("table_name")).isEqualTo("payment_schedule");
                assertThat(result.getString("data_type")).isEqualTo("double precision");
            }
        }
    }

    private static void migrateTo(int target, String schema) {
        var config = Flyway.configure().dataSource(url(), user(), "")
                .locations("classpath:db/migration").target(String.valueOf(target));
        if (schema != null) {
            config.defaultSchema(schema).schemas(schema).initSql("SET search_path TO " + schema);
        }
        config.load().migrate();
    }

    private static FluentConfiguration flyway(String schema) {
        return Flyway.configure().dataSource(url(), user(), "")
                .defaultSchema(schema).schemas(schema).initSql("SET search_path TO " + schema)
                .locations("classpath:db/migration");
    }

    private static long legacyCase(Statement sql) throws SQLException {
        sql.execute("""
                insert into client_user(dpi, first_name, last_name, email, phone, created_at)
                values('1000000000808', 'Ana', 'Pérez', 'ana808@example.test', '55550001', '2022-01-01')
                """);
        sql.execute("insert into process_type(id, name, status) values(802, 'Escritura', 'PUBLISHED')");
        sql.execute("""
                insert into legal_process(dpi_client, id_user_system_assigned, id_process_type,
                    current_status, created_at, case_code, process_type_name_snapshot,
                    process_type_version_snapshot)
                values('1000000000808', '3002234560901', 802, 'OPEN', '2022-01-01', 'EXP-802', 'Escritura', 0)
                """);
        try (var result = sql.executeQuery("select id from legal_process where case_code = 'EXP-802'")) {
            result.next();
            return result.getLong(1);
        }
    }

    private static void assertRejected(Statement sql, long caseId, String amount,
                                       String type, String concept, int daysAhead) {
        assertThatThrownBy(() -> insert(sql, caseId, amount, type, concept, daysAhead))
                .as("debería rechazar %s %s '%s'", amount, type, concept)
                .isInstanceOf(SQLException.class);
    }

    private static void insert(Statement sql, long caseId, String amount, String type,
                               String concept, int daysAhead) throws SQLException {
        insert(sql, caseId, amount, type, concept, daysAhead, null, null);
    }

    private static void insert(Statement sql, long caseId, String amount, String type,
                               String concept, int daysAhead, String requestId, String hash) throws SQLException {
        sql.execute("""
                insert into case_payment(id, legal_process_id, amount, payment_type, payment_method,
                    concept, payment_date, registered_by, request_id, request_hash)
                values(gen_random_uuid(), %d, %s, '%s', 'EFECTIVO', '%s',
                    (current_date + %d), '3002234560901', %s, %s)
                """.formatted(caseId, amount, type, concept, daysAhead,
                requestId == null ? "null" : "'" + requestId + "'",
                hash == null ? "null" : "'" + hash + "'"));
    }
}