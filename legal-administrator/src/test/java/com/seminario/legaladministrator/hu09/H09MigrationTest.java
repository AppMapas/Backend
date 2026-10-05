package com.seminario.legaladministrator.hu09;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.sql.*;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

@EnabledIfSystemProperty(named="h09.integration", matches="true")
class H09MigrationTest {
    private String url() {
        String url = System.getProperty("h09.test.url", "");
        if (!url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/h09_test"))
            throw new IllegalArgumentException("Usa la base desechable h09_test.");
        return url;
    }
    private String user() { return System.getProperty("h09.test.user", "krm"); }
    private void migrate(String schema, int target) {
        Flyway.configure().dataSource(url(), user(), "").locations("classpath:db/migration")
                .schemas(schema).defaultSchema(schema).initSql("SET search_path TO " + schema)
                .target(String.valueOf(target)).load().migrate();
    }
    @Test void legacyExpensesStopMigrationWithoutChangingTheirData() throws Exception {
        String schema = "h09_legacy_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = DriverManager.getConnection(url(), user(), ""); Statement sql = connection.createStatement()) {
            sql.execute("create schema " + schema);
            try {
                migrate(schema, 9);
                sql.execute("SET search_path TO " + schema);
                sql.execute("insert into payment_category(id,name) values(900,'Hereditario')");
                sql.execute("insert into expense_record(scope,id_payment_category,amount,description,expense_date) values('OFICINA',900,10.005,'Original','2020-01-01')");
                assertThatThrownBy(() -> migrate(schema, 10)).hasMessageContaining("H09: concilia");
                try (var row = sql.executeQuery("select amount,description from expense_record")) {
                    assertThat(row.next()).isTrue();
                    assertThat(row.getDouble(1)).isEqualTo(10.005);
                    assertThat(row.getString(2)).isEqualTo("Original");
                }
                try (var row = sql.executeQuery("select count(*) from information_schema.columns where table_schema='" + schema + "' and table_name='expense_record' and column_name='registered_by'")) {
                    row.next(); assertThat(row.getInt(1)).isZero();
                }
            } finally { sql.execute("SET search_path TO public"); sql.execute("drop schema " + schema + " cascade"); }
        }
    }

    @Test void keepsExistingPaymentsAndProtectsMoneyAuditAndDeletion() throws Exception {
        String schema = "h09_upgrade_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = DriverManager.getConnection(url(), user(), ""); Statement sql = connection.createStatement()) {
            sql.execute("create schema " + schema);
            try {
                migrate(schema, 9);
                sql.execute("SET search_path TO " + schema);
                // Una categoría con id manual se preserva y no colisiona con los nuevos códigos.
                sql.execute("insert into payment_category(id,name) values(1,'Categoría previa')");
                sql.execute("insert into client_user(dpi,first_name,last_name,email,phone,created_at) values('1000000000909','Ana','Caja','h09@example.test','55550000','2020-01-01')");
                sql.execute("insert into process_type(id,name,status) values(900,'Caja','PUBLISHED')");
                sql.execute("""
                        insert into legal_process(id,dpi_client,id_user_system_assigned,id_process_type,current_status,
                            created_at,case_code,process_type_name_snapshot,process_type_version_snapshot)
                        values(900,'1000000000909','3002234560901',900,'OPEN','2020-01-01','EXP-900','Caja',0)
                        """);
                sql.execute("""
                        insert into case_payment(id,legal_process_id,amount,payment_type,payment_method,concept,payment_date,registered_by)
                        values('11111111-1111-4111-8111-111111111111',900,10.25,'ABONO','EFECTIVO','Original','2020-01-01','3002234560901')
                        """);
                migrate(schema, 10);
                try (var row = sql.executeQuery("select code,active from payment_category where id=1")) {
                    row.next();
                    assertThat(row.getString(1)).isEqualTo("LEGACY_1");
                    assertThat(row.getBoolean(2)).isFalse();
                }
                try (var row = sql.executeQuery("select amount::text from cash_movement where source='CASE_PAYMENT'")) {
                    assertThat(row.next()).isTrue(); assertThat(row.getString(1)).isEqualTo("10.25");
                    assertThat(row.next()).isFalse();
                }
                assertThatThrownBy(() -> sql.execute("update case_payment set amount=50 where legal_process_id=900")).isInstanceOf(SQLException.class);
                assertThatThrownBy(() -> sql.execute("update case_payment set active=false where legal_process_id=900")).isInstanceOf(SQLException.class);
                assertThatThrownBy(() -> sql.execute("delete from case_payment where legal_process_id=900")).isInstanceOf(SQLException.class);
                sql.execute("""
                        insert into expense_record(scope,id_payment_category,amount,description,expense_date,payment_method,
                            registered_by,request_id,request_hash)
                        values('OFICINA',(select id from payment_category where code='UTILES_OFICINA'),0.10,'Papel','2020-01-01',
                            'EFECTIVO','3002234560901',gen_random_uuid(),repeat('a',64))
                        """);
                assertThatThrownBy(() -> sql.execute("update expense_record set amount=0.20")).isInstanceOf(SQLException.class);
                assertThatThrownBy(() -> sql.execute("update expense_record set owner_dpi='3001123450101'")).isInstanceOf(SQLException.class);
                assertThatThrownBy(() -> sql.execute("delete from expense_record")).isInstanceOf(SQLException.class);
                sql.execute("update expense_record set active=false,annulled_by='3002234560901',annulled_at=now(),annul_reason='Error',version=version+1");
                assertThatThrownBy(() -> sql.execute("update expense_record set active=true,annul_reason=null,annulled_by=null,annulled_at=null")).isInstanceOf(SQLException.class);
                assertThatThrownBy(() -> sql.execute("""
                        insert into expense_record(scope,id_payment_category,amount,description,expense_date,payment_method,
                            registered_by,owner_dpi,request_id,request_hash)
                        values('PERSONAL',(select id from payment_category where code='UTILES_OFICINA'),1,'Incompatible','2020-01-01',
                            'EFECTIVO','3002234560901','3002234560901',gen_random_uuid(),repeat('b',64))
                        """)).isInstanceOf(SQLException.class);
            } finally { sql.execute("SET search_path TO public"); sql.execute("drop schema " + schema + " cascade"); }
        }
    }
}
