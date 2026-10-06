package com.seminario.legaladministrator.modules.agenda;

import static org.assertj.core.api.Assertions.*;

import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@EnabledIfSystemProperty(named = "agenda.integration", matches = "true")
class AgendaMigrationTest {

    @Test
    void individualConnectionsPreserveTheConnectedLegacyAccountAndPendingWork() {
        String base = System.getProperty("agenda.test.url", "");
        if (
            !base.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/agenda_test")
        ) throw new IllegalArgumentException("Usa agenda_test local desechable.");
        String schema = "agenda_upgrade_" + UUID.randomUUID().toString().replace("-", "");
        var ds = new DriverManagerDataSource(
            base + "?currentSchema=" + schema,
            System.getProperty("user.name"),
            ""
        );
        var jdbc = new JdbcTemplate(ds);
        try {
            Flyway.configure()
                .dataSource(ds)
                .schemas(schema)
                .defaultSchema(schema)
                .locations("classpath:db/migration")
                .target("11")
                .load()
                .migrate();
            jdbc.update(
                "update google_calendar_connection set owner_dpi='3002234560901',google_subject='legacy-subject',calendar_id='office',encrypted_refresh_token='encrypted-legacy-token',key_version='v1',state='CONNECTED' where id=1"
            );
            jdbc.update(
                """
                insert into legal_process_calendar(id,event_title,event_date,activity_type,starts_at,ends_at,time_zone,all_day,responsible_dpi,status,version,request_id,request_hash,created_by)
                values(901,'Consulta','2027-01-01T09:00:00','FOLLOW_UP','2027-01-01T15:00:00Z','2027-01-01T16:00:00Z','America/Guatemala',false,'3002234560901','SCHEDULED',2,?,'hash','3002234560901')
                """,
                UUID.randomUUID()
            );
            jdbc.update("insert into agenda_sync_outbox(event_id,event_version) values(901,2)");
            jdbc.update(
                "insert into agenda_google_event(event_id,calendar_id,google_event_id,state,synced_version) values(901,'office','legacy-event','SYNCED',2)"
            );
            Flyway.configure()
                .dataSource(ds)
                .schemas(schema)
                .defaultSchema(schema)
                .locations("classpath:db/migration")
                .load()
                .migrate();
            assertThat(
                jdbc.queryForObject(
                    "select encrypted_refresh_token from google_calendar_connection where id=1",
                    String.class
                )
            ).isEqualTo("encrypted-legacy-token");
            assertThat(
                jdbc.queryForObject("select connection_id from agenda_sync_outbox", Long.class)
            ).isEqualTo(1);
            assertThat(
                jdbc.queryForObject(
                    "select master_revision from legal_process_calendar where id=901",
                    Long.class
                )
            ).isEqualTo(2);
            assertThat(
                jdbc.queryForObject("select synced_master_revision from agenda_google_event", Long.class)
            ).isEqualTo(2);
            assertThat(
                jdbc.queryForObject(
                    "insert into google_calendar_connection(owner_dpi,state) select dpi,'DISCONNECTED' from user_system where dpi<>'3002234560901' order by dpi limit 1 returning id",
                    Long.class
                )
            ).isGreaterThan(1);
        } finally {
            jdbc.execute("drop schema if exists " + schema + " cascade");
        }
    }

    @Test
    void legacyEventsStopMigrationWithoutLosingData() {
        String base = System.getProperty("agenda.test.url", "");
        if (
            !base.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/agenda_test")
        ) throw new IllegalArgumentException("Usa agenda_test local desechable.");
        String schema = "agenda_legacy_" + UUID.randomUUID().toString().replace("-", "");
        var ds = new DriverManagerDataSource(
            base + "?currentSchema=" + schema,
            System.getProperty("user.name"),
            ""
        );
        var jdbc = new JdbcTemplate(ds);
        try {
            Flyway.configure()
                .dataSource(ds)
                .schemas(schema)
                .defaultSchema(schema)
                .locations("classpath:db/migration")
                .target("10")
                .load()
                .migrate();
            jdbc.execute(
                "insert into client_user(dpi,first_name,last_name,email,phone,created_at) values('1000000000011','Ana','Original','legacy@example.test','55550000','2020-01-01')"
            );
            jdbc.execute("insert into process_type(id,name,status) values(901,'Agenda','PUBLISHED')");
            jdbc.execute(
                """
                insert into legal_process(id,dpi_client,id_user_system_assigned,id_process_type,current_status,created_at,
                    case_code,process_type_name_snapshot,process_type_version_snapshot)
                values(901,'1000000000011','3002234560901',901,'OPEN','2020-01-01','EXP-901','Agenda',0)
                """
            );
            jdbc.execute(
                "insert into legal_process_calendar(id_legal_process,event_title,event_date) values(901,'Evento original','2020-01-01')"
            );
            assertThatThrownBy(() ->
                Flyway.configure()
                    .dataSource(ds)
                    .schemas(schema)
                    .defaultSchema(schema)
                    .locations("classpath:db/migration")
                    .load()
                    .migrate()
            ).hasMessageContaining("concilia");
            assertThat(
                jdbc.queryForObject("select event_title from legal_process_calendar", String.class)
            ).isEqualTo("Evento original");
            assertThat(
                jdbc.queryForObject(
                    "select count(*) from information_schema.columns where table_schema=? and table_name='legal_process_calendar' and column_name='starts_at'",
                    Long.class,
                    schema
                )
            ).isZero();
        } finally {
            jdbc.execute("drop schema if exists " + schema + " cascade");
        }
    }
}
