package com.seminario.legaladministrator.modules.agenda;

import static com.seminario.legaladministrator.modules.agenda.AgendaDtos.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.seminario.legaladministrator.config.security.OfficeAccess;
import com.seminario.legaladministrator.modules.processes.repository.LegalProcessRepository;
import com.seminario.legaladministrator.modules.processes.service.CaseRequestGuard;
import com.seminario.legaladministrator.modules.users.*;
import com.seminario.legaladministrator.modules.users.repository.ClientUserRepository;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;

@EnabledIfSystemProperty(named = "agenda.integration", matches = "true")
class AgendaPersistenceTest {

    JdbcTemplate jdbc;
    TransactionTemplate tx;
    String schema;
    AgendaRepository repo;
    String url;
    String actor = "3002234560901";

    @BeforeEach
    void setup() {
        String base = System.getProperty("agenda.test.url", "");
        if (
            !base.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/agenda_test")
        ) throw new IllegalArgumentException("Usa únicamente agenda_test local desechable.");
        schema = "agenda_" + UUID.randomUUID().toString().replace("-", "");
        url = base + "?currentSchema=" + schema + "&options=-c%20TimeZone%3DUTC";
        var ds = new DriverManagerDataSource(url, System.getProperty("user.name"), "");
        Flyway.configure()
            .dataSource(ds)
            .schemas(schema)
            .defaultSchema(schema)
            .locations("classpath:db/migration")
            .load()
            .migrate();
        jdbc = new JdbcTemplate(ds);
        tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        repo = new AgendaRepository(jdbc);
        jdbc.update(
            "insert into client_user(dpi,first_name,last_name,email,phone,created_at) values('1000000000001','Ana','Agenda','agenda@example.test','55550000','2020-01-01')"
        );
    }

    @AfterEach
    void cleanup() {
        if (jdbc != null) jdbc.execute("drop schema " + schema + " cascade");
    }

    EventRequest request(UUID id) {
        Instant start = Instant.now().plusSeconds(86400).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        return new EventRequest(
            "Consulta privada",
            "Datos privados",
            ActivityType.APPOINTMENT,
            start,
            start.plusSeconds(3600),
            "America/Guatemala",
            false,
            "Oficina",
            "1000000000001",
            null,
            id,
            0L
        );
    }

    AgendaService service() {
        OfficeAccess access = mock(OfficeAccess.class);
        var user = new UserSystemEntity();
        user.setDpi(actor);
        when(access.current()).thenReturn(user);
        var clients = mock(ClientUserRepository.class);
        var client = new ClientUserEntity();
        client.setActive(true);
        client.setDpi("1000000000001");
        when(clients.findById(client.getDpi())).thenReturn(Optional.of(client));
        return new AgendaService(
            repo,
            access,
            mock(LegalProcessRepository.class),
            clients,
            new CaseRequestGuard(jdbc)
        );
    }

    @Test
    void persistsEventHistoryOutboxAndIdempotentRetryTogether() {
        var service = service();
        EventRequest request = request(UUID.randomUUID());
        Creation first = tx.execute(s -> service.create(request));
        assertThat(first).isNotNull();
        assertThat(first.event().startsAt()).isEqualTo(
            request.startsAt().truncatedTo(java.time.temporal.ChronoUnit.MICROS)
        );
        assertThat(tx.execute(s -> service.create(request)).replayed()).isTrue();
        assertThat(jdbc.queryForObject("select count(*) from legal_process_calendar", Long.class)).isEqualTo(
            1
        );
        assertThat(jdbc.queryForObject("select count(*) from agenda_event_history", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from agenda_sync_outbox", Long.class)).isEqualTo(1);
        assertThat(
            repo
                .search(
                    request.startsAt().minusSeconds(1),
                    request.endsAt().plusSeconds(1),
                    null,
                    null,
                    null,
                    0,
                    25
                )
                .totalElements()
        ).isEqualTo(1);
        assertThat(
            repo.days(request.startsAt().minusSeconds(1), request.endsAt().plusSeconds(1), null, null, null)
        ).hasSize(1);
        assertThat(repo.history(first.event().id())).hasSize(1);
        tx.executeWithoutResult(s ->
            service.changeStatus(
                first.event().id(),
                new StatusRequest(EventStatus.CANCELLED, 0L, "Cambio de fecha")
            )
        );
        assertThat(repo.find(first.event().id()).orElseThrow().status()).isEqualTo("CANCELLED");
        assertThat(repo.history(first.event().id())).hasSize(2);
        assertThat(jdbc.queryForObject("select count(*) from agenda_sync_outbox", Long.class)).isEqualTo(2);
    }

    @Test
    void concurrentRequestsCannotReserveTheSameSlot() throws Exception {
        var service = service();
        var a = request(UUID.randomUUID());
        var b = new EventRequest(
            a.title(),
            a.description(),
            a.type(),
            a.startsAt(),
            a.endsAt(),
            a.timeZone(),
            false,
            a.location(),
            a.clientDpi(),
            null,
            UUID.randomUUID(),
            0L
        );
        var pool = Executors.newFixedThreadPool(2);
        var gate = new CountDownLatch(1);
        try {
            Callable<Boolean> first = () -> {
                gate.await();
                try {
                    tx.execute(s -> service.create(a));
                    return true;
                } catch (com.seminario.legaladministrator.shared.OperationException e) {
                    return false;
                }
            };
            Callable<Boolean> second = () -> {
                gate.await();
                try {
                    tx.execute(s -> service.create(b));
                    return true;
                } catch (com.seminario.legaladministrator.shared.OperationException e) {
                    return false;
                }
            };
            var f = pool.submit(first);
            var g = pool.submit(second);
            gate.countDown();
            assertThat(
                List.of(f.get(10, TimeUnit.SECONDS), g.get(10, TimeUnit.SECONDS))
            ).containsExactlyInAnyOrder(true, false);
            assertThat(
                jdbc.queryForObject("select count(*) from legal_process_calendar", Long.class)
            ).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void failureRollsBackEventHistoryAndOutbox() {
        var r = request(UUID.randomUUID());
        assertThatThrownBy(() ->
            tx.executeWithoutResult(s -> {
                service().create(r);
                throw new IllegalStateException("Simulated rollback");
            })
        ).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("select count(*) from legal_process_calendar", Long.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from agenda_event_history", Long.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from agenda_sync_outbox", Long.class)).isZero();
    }

    @Test
    void googleFailureKeepsAgendaAndSchedulesRetryWithoutLeakingTokens() {
        EventRequest r = request(UUID.randomUUID());
        tx.executeWithoutResult(s -> service().create(r));
        var props = new GoogleCalendarProperties();
        props.setEnabled(true);
        props.setClientId("client");
        props.setClientSecret("secret");
        props.setCalendarId("office");
        props.setEncryptionKey(Base64.getEncoder().encodeToString(new byte[32]));
        var cipher = new GoogleTokenCipher(props);
        var google = mock(GoogleCalendarGateway.class);
        jdbc.update(
            "insert into google_calendar_connection(owner_dpi,google_subject,calendar_id,encrypted_refresh_token,key_version,state) values(?,'subject','office',?,'v1','CONNECTED')",
            actor,
            cipher.encrypt("refresh", actor, "subject")
        );
        jdbc.update(
            "update agenda_sync_outbox set connection_id=(select id from google_calendar_connection where owner_dpi=?)",
            actor
        );
        when(google.refresh("refresh")).thenThrow(new GoogleCalendarGateway.Failure("TEMPORARY"));
        new AgendaSyncWorker(jdbc, tx, props, google, cipher, repo).tick();
        assertThat(jdbc.queryForObject("select attempts from agenda_sync_outbox", Integer.class)).isEqualTo(
            1
        );
        assertThat(jdbc.queryForObject("select last_error from agenda_sync_outbox", String.class)).isEqualTo(
            "TEMPORARY"
        );
        assertThat(repo.findRequest(r.requestId()).orElseThrow().syncState()).isEqualTo("ERROR");
        assertThat(
            jdbc.queryForObject(
                "select encrypted_refresh_token from google_calendar_connection",
                String.class
            )
        ).doesNotContain("refresh");
    }

    @Test
    void oauthConnectionUsesSingleIntentAndStoresOnlyEncryptedCredential() {
        var properties = new GoogleCalendarProperties();
        properties.setEnabled(true);
        properties.setClientId("client");
        properties.setClientSecret("secret");
        properties.setCalendarId("office");
        properties.setEncryptionKey(Base64.getEncoder().encodeToString(new byte[32]));
        var cipher = new GoogleTokenCipher(properties);
        var google = mock(GoogleCalendarGateway.class);
        var access = mock(OfficeAccess.class);
        var user = new UserSystemEntity();
        user.setDpi(actor);
        var role = new RoleEntity();
        role.setName("Abogada");
        user.setRole(role);
        when(access.current()).thenReturn(user);
        var connection = new GoogleConnectionService(
            jdbc,
            tx,
            access,
            properties,
            google,
            cipher,
            repo,
            service()
        );
        when(google.exchange("code")).thenReturn(
            Map.of(
                "access_token",
                "access",
                "refresh_token",
                "refresh-secret",
                "scope",
                GoogleCalendarProperties.SCOPE
            )
        );
        when(google.identity("access")).thenReturn(
            Map.of("sub", "subject", "email", "office@example.test", "email_verified", true)
        );
        var intent = connection.begin("http://localhost:5173", "XmlHttpRequest");
        var request = new ConnectRequest("code", intent.state());
        assertThat(connection.connect(request, "http://localhost:5173", "XmlHttpRequest").state()).isEqualTo(
            "CONNECTED"
        );
        assertThat(
            jdbc.queryForObject(
                "select encrypted_refresh_token from google_calendar_connection",
                String.class
            )
        ).doesNotContain("refresh-secret");
        assertThatThrownBy(() ->
            connection.connect(request, "http://localhost:5173", "XmlHttpRequest")
        ).hasMessageContaining("venció");
        verify(google, times(1)).exchange("code");
        assertThat(connection.disconnect().state()).isEqualTo("DISCONNECTED");
        assertThat(
            jdbc.queryForObject(
                "select encrypted_refresh_token from google_calendar_connection",
                String.class
            )
        ).isNull();
        verify(google).revoke("refresh-secret");
    }

    @Test
    void resolvingExternalConflictCreatesAuditedRevisionAndUsesTheNewEtag() {
        var request = request(UUID.randomUUID());
        var created = tx.execute(s -> service().create(request));
        var properties = new GoogleCalendarProperties();
        properties.setEnabled(true);
        properties.setClientId("client");
        properties.setClientSecret("secret");
        properties.setCalendarId("office");
        properties.setEncryptionKey(Base64.getEncoder().encodeToString(new byte[32]));
        var cipher = new GoogleTokenCipher(properties);
        var google = mock(GoogleCalendarGateway.class);
        var access = mock(OfficeAccess.class);
        var user = new UserSystemEntity();
        user.setDpi(actor);
        var role = new RoleEntity();
        role.setName("Abogada");
        user.setRole(role);
        when(access.current()).thenReturn(user);
        jdbc.update(
            "insert into google_calendar_connection(owner_dpi,google_subject,calendar_id,encrypted_refresh_token,key_version,state) values(?,'subject','office',?,'v1','CONNECTED')",
            actor,
            cipher.encrypt("refresh", actor, "subject")
        );
        jdbc.update(
            "update agenda_sync_outbox set connection_id=(select id from google_calendar_connection where owner_dpi=?)",
            actor
        );
        jdbc.update(
            "insert into agenda_google_event(event_id,calendar_id,google_event_id,etag,state) values(?,'office','legal1','old-tag','CONFLICT')",
            created.event().id()
        );
        when(google.refresh("refresh")).thenReturn("access");
        when(google.currentEtag("access", "office", "legal1", created.event().id())).thenReturn(
            Optional.of("fresh-tag")
        );
        var connection = new GoogleConnectionService(
            jdbc,
            tx,
            access,
            properties,
            google,
            cipher,
            repo,
            service()
        );
        var reconciled = connection.reconcile(created.event().id(), new ReconcileRequest(0L, true));
        assertThat(reconciled.version()).isEqualTo(1);
        assertThat(repo.history(reconciled.id()).get(0).action()).isEqualTo("GOOGLE_RECONCILED");
        when(google.publish(eq("access"), eq("office"), eq("legal1"), eq("fresh-tag"), any())).thenReturn(
            new GoogleCalendarGateway.Synced("synced-tag")
        );
        new AgendaSyncWorker(jdbc, tx, properties, google, cipher, repo).tick();
        verify(google).publish(eq("access"), eq("office"), eq("legal1"), eq("fresh-tag"), any());
        assertThat(repo.find(reconciled.id()).orElseThrow().syncState()).isEqualTo("SYNCED");
    }

    @Test
    void invalidGoogleGrantRequiresReconnectAndSuccessfulSyncIsNotRepeated() {
        var r = request(UUID.randomUUID());
        tx.executeWithoutResult(s -> service().create(r));
        var props = new GoogleCalendarProperties();
        props.setEnabled(true);
        props.setClientId("client");
        props.setClientSecret("secret");
        props.setCalendarId("office");
        props.setEncryptionKey(Base64.getEncoder().encodeToString(new byte[32]));
        var cipher = new GoogleTokenCipher(props);
        var google = mock(GoogleCalendarGateway.class);
        jdbc.update(
            "insert into google_calendar_connection(owner_dpi,google_subject,calendar_id,encrypted_refresh_token,key_version,state) values(?,'subject','office',?,'v1','CONNECTED')",
            actor,
            cipher.encrypt("refresh", actor, "subject")
        );
        jdbc.update(
            "update agenda_sync_outbox set connection_id=(select id from google_calendar_connection where owner_dpi=?)",
            actor
        );
        when(google.refresh("refresh")).thenThrow(new GoogleCalendarGateway.Failure("REAUTH_REQUIRED"));
        var worker = new AgendaSyncWorker(jdbc, tx, props, google, cipher, repo);
        worker.tick();
        assertThat(
            jdbc.queryForObject("select state from google_calendar_connection", String.class)
        ).isEqualTo("REAUTH_REQUIRED");
        jdbc.update("update google_calendar_connection set state='CONNECTED'");
        jdbc.update("update agenda_sync_outbox set attempts=0,next_attempt_at=now()");
        doReturn("access").when(google).refresh("refresh");
        when(google.publish(eq("access"), eq("office"), anyString(), isNull(), any())).thenReturn(
            new GoogleCalendarGateway.Synced("etag")
        );
        worker.tick();
        worker.tick();
        assertThat(repo.findRequest(r.requestId()).orElseThrow().syncState()).isEqualTo("SYNCED");
        verify(google, times(1)).publish(eq("access"), eq("office"), anyString(), isNull(), any());
    }

    @Test
    void recurrenceUsesOneMasterAndOneExceptionWithoutChangingOtherOccurrences() {
        var base = request(UUID.randomUUID());
        var until = base
            .startsAt()
            .atZone(java.time.ZoneId.of("America/Guatemala"))
            .toLocalDate()
            .plusDays(2);
        var repeating = new EventRequest(
            base.title(),
            base.description(),
            base.type(),
            base.startsAt(),
            base.endsAt(),
            base.timeZone(),
            base.allDay(),
            base.location(),
            base.clientDpi(),
            base.caseId(),
            base.requestId(),
            base.version(),
            new Recurrence(Frequency.DAILY, until)
        );
        var service = service();
        var saved = tx.execute(s -> service.create(repeating)).event();
        var starts = AgendaRecurrence.starts(saved.startsAt(), saved.timeZone(), saved.recurrence());
        var second = repo.occurrence(saved, starts.get(1));
        var moved = new EventRequest(
            second.title(),
            second.description(),
            ActivityType.APPOINTMENT,
            second.startsAt().plusSeconds(7200),
            second.endsAt().plusSeconds(7200),
            second.timeZone(),
            second.allDay(),
            second.location(),
            second.clientDpi(),
            second.caseId(),
            null,
            second.version()
        );
        tx.executeWithoutResult(s ->
            service.updateOccurrence(saved.id(), new OccurrenceRequest(starts.get(1), moved))
        );
        var all = repo
            .search(
                saved.startsAt().minusSeconds(1),
                saved.startsAt().plusSeconds(4 * 86400),
                null,
                null,
                null,
                0,
                25
            )
            .content();
        assertThat(all).hasSize(3);
        assertThat(all.get(0).startsAt()).isEqualTo(starts.get(0));
        assertThat(all.get(1).startsAt()).isEqualTo(moved.startsAt());
        assertThat(all.get(2).startsAt()).isEqualTo(starts.get(2));
        assertThat(jdbc.queryForObject("select count(*) from legal_process_calendar", Long.class)).isEqualTo(
            1
        );
        assertThat(jdbc.queryForObject("select count(*) from agenda_event_exception", Long.class)).isEqualTo(
            1
        );
        tx.executeWithoutResult(s ->
            service.changeOccurrenceStatus(
                saved.id(),
                new OccurrenceStatusRequest(
                    starts.get(1),
                    new StatusRequest(EventStatus.CANCELLED, 1L, "Cambio")
                )
            )
        );
        assertThat(
            repo
                .search(
                    saved.startsAt().minusSeconds(1),
                    saved.startsAt().plusSeconds(4 * 86400),
                    null,
                    null,
                    "SCHEDULED",
                    0,
                    25
                )
                .content()
        ).hasSize(2);
        assertThat(repo.history(saved.id()).get(0).originalStartsAt()).isEqualTo(starts.get(1));
    }

    @Test
    void laterOccurrenceConflictsAreRejectedAtomically() {
        var first = request(UUID.randomUUID());
        var later = new EventRequest(
            first.title(),
            first.description(),
            first.type(),
            first.startsAt().plusSeconds(86400),
            first.endsAt().plusSeconds(86400),
            first.timeZone(),
            false,
            first.location(),
            first.clientDpi(),
            null,
            UUID.randomUUID(),
            0L
        );
        var service = service();
        tx.executeWithoutResult(s -> service.create(later));
        var repeating = new EventRequest(
            first.title(),
            first.description(),
            first.type(),
            first.startsAt(),
            first.endsAt(),
            first.timeZone(),
            false,
            first.location(),
            first.clientDpi(),
            null,
            first.requestId(),
            0L,
            new Recurrence(
                Frequency.DAILY,
                first.startsAt().atZone(java.time.ZoneId.of(first.timeZone())).toLocalDate().plusDays(2)
            )
        );
        assertThatThrownBy(() -> tx.execute(s -> service.create(repeating))).hasMessageContaining("coincide");
        assertThat(jdbc.queryForObject("select count(*) from legal_process_calendar", Long.class)).isEqualTo(
            1
        );
    }

    @Test
    void separateUsersKeepTheirOwnEncryptedConnectionsAndDisconnectIndependently() {
        String other = jdbc.queryForObject(
            "select dpi from user_system where dpi<>? order by dpi limit 1",
            String.class,
            actor
        );
        var properties = new GoogleCalendarProperties();
        properties.setEnabled(true);
        properties.setClientId("client");
        properties.setClientSecret("secret");
        properties.setCalendarId("office");
        properties.setEncryptionKey(Base64.getEncoder().encodeToString(new byte[32]));
        var google = mock(GoogleCalendarGateway.class);
        var access = mock(OfficeAccess.class);
        var user = new UserSystemEntity();
        user.setDpi(actor);
        when(access.current()).thenReturn(user);
        var connection = new GoogleConnectionService(
            jdbc,
            tx,
            access,
            properties,
            google,
            new GoogleTokenCipher(properties),
            repo,
            service()
        );
        when(google.exchange("code-one")).thenReturn(
            Map.of(
                "access_token",
                "access-one",
                "refresh_token",
                "refresh-one",
                "scope",
                GoogleCalendarProperties.SCOPE
            )
        );
        when(google.exchange("code-two")).thenReturn(
            Map.of(
                "access_token",
                "access-two",
                "refresh_token",
                "refresh-two",
                "scope",
                GoogleCalendarProperties.SCOPE
            )
        );
        when(google.identity("access-one")).thenReturn(
            Map.of("sub", "subject-one", "email", "one@example.test", "email_verified", true)
        );
        when(google.identity("access-two")).thenReturn(
            Map.of("sub", "subject-two", "email", "two@example.test", "email_verified", true)
        );
        var first = connection.begin("http://localhost:5173", "XmlHttpRequest");
        connection.connect(
            new ConnectRequest("code-one", first.state()),
            "http://localhost:5173",
            "XmlHttpRequest"
        );
        user.setDpi(other);
        assertThat(connection.status().state()).isEqualTo("DISCONNECTED");
        var second = connection.begin("http://localhost:5173", "XmlHttpRequest");
        connection.connect(
            new ConnectRequest("code-two", second.state()),
            "http://localhost:5173",
            "XmlHttpRequest"
        );
        assertThat(connection.status().accountEmail()).isEqualTo("two@example.test");
        connection.disconnect();
        user.setDpi(actor);
        assertThat(connection.status().state()).isEqualTo("CONNECTED");
        assertThat(connection.status().accountEmail()).isEqualTo("one@example.test");
        assertThat(
            jdbc.queryForObject("select count(*) from google_calendar_connection", Long.class)
        ).isEqualTo(2);
        verify(google).revoke("refresh-two");
        verify(google, never()).revoke("refresh-one");
    }

    @Test
    void theWorkerPublishesTheSeriesOnceAndUpdatesOnlyTheChangedOccurrence() {
        var base = request(UUID.randomUUID());
        var until = base.startsAt().atZone(java.time.ZoneId.of(base.timeZone())).toLocalDate().plusDays(2);
        var recurring = new EventRequest(
            base.title(),
            base.description(),
            base.type(),
            base.startsAt(),
            base.endsAt(),
            base.timeZone(),
            false,
            base.location(),
            base.clientDpi(),
            null,
            base.requestId(),
            0L,
            new Recurrence(Frequency.DAILY, until)
        );
        var properties = new GoogleCalendarProperties();
        properties.setEnabled(true);
        properties.setClientId("client");
        properties.setClientSecret("secret");
        properties.setCalendarId("office");
        properties.setEncryptionKey(Base64.getEncoder().encodeToString(new byte[32]));
        var cipher = new GoogleTokenCipher(properties);
        var google = mock(GoogleCalendarGateway.class);
        jdbc.update(
            "insert into google_calendar_connection(owner_dpi,google_subject,calendar_id,encrypted_refresh_token,key_version,state) values(?,'subject','office',?,'v1','CONNECTED')",
            actor,
            cipher.encrypt("refresh", actor, "subject")
        );
        var service = service();
        var saved = tx.execute(t -> service.create(recurring)).event();
        when(google.refresh("refresh")).thenReturn("access");
        when(google.publish(eq("access"), eq("office"), anyString(), any(), any())).thenReturn(
            new GoogleCalendarGateway.Synced("etag")
        );
        var worker = new AgendaSyncWorker(jdbc, tx, properties, google, cipher, repo);
        worker.tick();
        var starts = AgendaRecurrence.starts(saved.startsAt(), saved.timeZone(), saved.recurrence());
        var occurrence = repo.occurrence(repo.find(saved.id()).orElseThrow(), starts.get(1));
        var moved = new EventRequest(
            occurrence.title(),
            occurrence.description(),
            ActivityType.APPOINTMENT,
            occurrence.startsAt().plusSeconds(7200),
            occurrence.endsAt().plusSeconds(7200),
            occurrence.timeZone(),
            false,
            occurrence.location(),
            occurrence.clientDpi(),
            null,
            null,
            occurrence.version()
        );
        tx.executeWithoutResult(t ->
            service.updateOccurrence(saved.id(), new OccurrenceRequest(starts.get(1), moved))
        );
        when(
            google.instance(
                eq("access"),
                eq("office"),
                anyString(),
                eq(starts.get(1)),
                eq("America/Guatemala")
            )
        ).thenReturn(Map.of("id", "instance1", "etag", "instance-tag"));
        worker.tick();
        worker.tick();
        verify(google, times(1)).publish(
            eq("access"),
            eq("office"),
            anyString(),
            isNull(),
            argThat(e -> e.originalStartsAt() == null)
        );
        verify(google, times(1)).publish(
            eq("access"),
            eq("office"),
            eq("instance1"),
            eq("instance-tag"),
            argThat(e -> starts.get(1).equals(e.originalStartsAt()) && moved.startsAt().equals(e.startsAt()))
        );
        assertThat(repo.find(saved.id()).orElseThrow().syncState()).isEqualTo("SYNCED");
        assertThat(
            jdbc.queryForObject("select synced_revision=revision from agenda_event_exception", Boolean.class)
        ).isTrue();
    }
}
