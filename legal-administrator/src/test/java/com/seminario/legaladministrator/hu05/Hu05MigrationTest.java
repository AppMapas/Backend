package com.seminario.legaladministrator.hu05;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.sql.DriverManager;
import java.sql.SQLException;
import static org.assertj.core.api.Assertions.*;

@EnabledIfSystemProperty(named = "hu05.integration", matches = "true")
class Hu05MigrationTest {
    @Test
    void upgradePreservesHistoricalDataAndPreventsCaseCodeCollisions() throws Exception {
        String url = System.getProperty("hu05.test.url", "");
        if (!url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/hu05_test")) {
            throw new IllegalArgumentException("Se requiere la base desechable hu05_test.");
        }
        String user = System.getProperty("hu05.test.user", "krm");
        String schema = "hu05_upgrade";
        try (var connection = DriverManager.getConnection(url, user, "");
             var sql = connection.createStatement()) {
            sql.execute("create schema hu05_upgrade");
            try {
                Flyway.configure().dataSource(url, user, "")
                        .defaultSchema(schema).schemas(schema)
                        .initSql("SET search_path TO hu05_upgrade")
                        .locations("classpath:db/migration").target("6").load().migrate();
                sql.execute("SET search_path TO hu05_upgrade");
                sql.execute("""
                        insert into client_user(dpi, first_name, last_name, email, phone, created_at)
                        values('legacy-123', 'Cliente', 'Histórico', 'legacy@example.test', '55551234', '2020-01-01')
                        """);
                sql.execute("""
                        insert into process_type(id, name, status) values(900, 'Trámite anterior', 'PUBLISHED')
                        """);
                sql.execute("""
                        insert into legal_process(dpi_client, id_user_system_assigned, id_process_type,
                            current_status, created_at, case_code, process_type_name_snapshot,
                            process_type_version_snapshot)
                        values('legacy-123', '3002234560901', 900, 'Presentado', '2020-01-01',
                            'EXP-900', 'Trámite anterior', 0)
                        """);
                Flyway.configure().dataSource(url, user, "")
                        .defaultSchema(schema).schemas(schema)
                        .initSql("SET search_path TO hu05_upgrade")
                        .locations("classpath:db/migration").load().migrate();
                try (var result = sql.executeQuery("""
                        select dpi, id_nationality, version, active from client_user where dpi = 'legacy-123'
                        """)) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getObject("id_nationality")).isNull();
                    assertThat(result.getLong("version")).isZero();
                    assertThat(result.getBoolean("active")).isTrue();
                }
                try (var result = sql.executeQuery("""
                        select case_code, current_status, opened_at::date from legal_process
                        """)) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getString("case_code")).isEqualTo("EXP-900");
                    assertThat(result.getString("current_status")).isEqualTo("Presentado");
                    assertThat(result.getDate(3).toString()).isEqualTo("2020-01-01");
                }
                try (var result = sql.executeQuery("select 'EXP-' || nextval('legal_process_case_code_seq')")) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getString(1)).isEqualTo("EXP-901");
                }
                assertThatThrownBy(() -> sql.execute("""
                        insert into client_user(dpi, first_name, last_name, email, phone, created_at)
                        values('bad-dpi', 'Ana', 'Pérez', 'ana@example.test', '55551234', current_date)
                        """)).isInstanceOf(SQLException.class);
            } finally {
                sql.execute("SET search_path TO public");
                sql.execute("drop schema hu05_upgrade cascade");
            }
        }
    }
}
