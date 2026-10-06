package com.seminario.legaladministrator.modules.agenda;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** Publica trabajos pendientes en Google. Cada trabajo usa la conexión de su operadora y conserva los conflictos para resolución explícita. */
@Component
@RequiredArgsConstructor
public class AgendaSyncWorker {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final GoogleCalendarProperties properties;
    private final GoogleCalendarGateway google;
    private final GoogleTokenCipher cipher;
    private final AgendaRepository events;

    private record Connection(
        long id,
        String owner,
        String subject,
        String calendar,
        String token,
        String keyVersion
    ) {}

    private record Job(long id, long eventId, long version, int attempts) {}

    private record Link(String externalId, String etag, long masterRevision) {}

    /** Procesa hasta diez trabajos por ciclo; cada trabajo se ejecuta en su propia transacción. */
    @Scheduled(fixedDelayString = "${app.agenda.google.sync-delay-ms:30000}")
    public void tick() {
        if (!properties.configured()) {
            return;
        }
        for (int count = 0; count < 10; count++) {
            Boolean processed = transactions.execute(tx -> process());
            if (!Boolean.TRUE.equals(processed)) {
                break;
            }
        }
    }

    /** Bloquea una conexión y un trabajo disponibles con SKIP LOCKED; descarta versiones superadas y publica cambios pendientes. */
    private boolean process() {
        jdbc.update(
            """
            update google_calendar_connection c set state='REAUTH_REQUIRED',version=version+1,updated_at=now()
            where state='CONNECTED' and not exists(select 1 from user_system u join role r on r.id=u.id_role
                where u.dpi=c.owner_dpi and r.name in ('Abogada','Administrador'))
            """
        );
        List<Connection> connections = jdbc.query(
            """
            select c.* from google_calendar_connection c where c.state='CONNECTED' and c.calendar_id=?
              and exists(select 1 from agenda_sync_outbox j where j.connection_id=c.id and j.completed_at is null and j.attempts<8 and j.next_attempt_at<=now())
            order by c.updated_at,c.id limit 1 for update skip locked
            """,
            (row, rowIndex) ->
                new Connection(
                    row.getLong("id"),
                    row.getString("owner_dpi"),
                    row.getString("google_subject"),
                    row.getString("calendar_id"),
                    row.getString("encrypted_refresh_token"),
                    row.getString("key_version")
                ),
            properties.getCalendarId()
        );
        if (connections.isEmpty()) {
            return false;
        }
        Connection connection = connections.get(0);
        List<Job> jobs = jdbc.query(
            """
            select * from agenda_sync_outbox where connection_id=? and completed_at is null and attempts<8 and next_attempt_at<=now()
            order by next_attempt_at,id limit 1 for update skip locked
            """,
            (row, rowIndex) ->
                new Job(
                    row.getLong("id"),
                    row.getLong("event_id"),
                    row.getLong("event_version"),
                    row.getInt("attempts")
                ),
            connection.id()
        );
        if (jobs.isEmpty()) {
            return false;
        }
        Job job = jobs.get(0);
        Long revision = jdbc.queryForObject(
            "select master_revision from legal_process_calendar where id=? for update",
            Long.class,
            job.eventId()
        );
        var event = events.find(job.eventId()).orElseThrow();
        if (event.version() != job.version()) {
            jdbc.update(
                "update agenda_sync_outbox set completed_at=now(),last_error='SUPERSEDED' where id=?",
                job.id()
            );
            return true;
        }
        String externalId =
            "legal" +
            UUID.nameUUIDFromBytes(
                (connection.calendar() + ":" + event.id()).getBytes(StandardCharsets.UTF_8)
            )
                .toString()
                .replace("-", "");
        jdbc.update(
            "insert into agenda_google_event(event_id,calendar_id,google_event_id) values(?,?,?) on conflict do nothing",
            event.id(),
            connection.calendar(),
            externalId
        );
        Link link = jdbc.queryForObject(
            "select google_event_id,etag,synced_master_revision from agenda_google_event where event_id=? and calendar_id=?",
            (row, rowIndex) -> new Link(row.getString(1), row.getString(2), row.getLong(3)),
            event.id(),
            connection.calendar()
        );
        try {
            String token = google.refresh(
                cipher.decrypt(
                    connection.token(),
                    connection.owner(),
                    connection.subject(),
                    connection.keyVersion()
                )
            );
            google.validateCalendar(token);
            Objects.requireNonNull(link);
            if (
                link.masterRevision() < Objects.requireNonNull(revision) || event.status().equals("CANCELLED")
            ) {
                var result = google.publish(
                    token,
                    connection.calendar(),
                    link.externalId(),
                    link.etag(),
                    event
                );
                jdbc.update(
                    "update agenda_google_event set etag=?,synced_master_revision=?,updated_at=now() where event_id=? and calendar_id=?",
                    result.etag(),
                    revision,
                    event.id(),
                    connection.calendar()
                );
            }
            if (event.recurrence() != null && event.status().equals("SCHEDULED")) {
                for (var exception : events.exceptions(event.id())) {
                    if (exception.revision() <= exception.syncedRevision()) {
                        continue;
                    }
                    String instanceId = exception.googleId();
                    String etag = exception.etag();
                    if (instanceId == null) {
                        var instance = google.instance(
                            token,
                            connection.calendar(),
                            link.externalId(),
                            exception.original(),
                            event.timeZone()
                        );
                        instanceId = GoogleCalendarGateway.required(instance, "id");
                        etag = GoogleCalendarGateway.required(instance, "etag");
                        jdbc.update(
                            "update agenda_event_exception set google_event_id=?,etag=? where event_id=? and original_starts_at=?",
                            instanceId,
                            etag,
                            event.id(),
                            Timestamp.from(exception.original())
                        );
                    }
                    var occurrence = events.occurrence(event, exception.original());
                    var result = google.publish(token, connection.calendar(), instanceId, etag, occurrence);
                    jdbc.update(
                        "update agenda_event_exception set etag=?,synced_revision=? where event_id=? and original_starts_at=?",
                        result.etag(),
                        exception.revision(),
                        event.id(),
                        Timestamp.from(exception.original())
                    );
                }
            }
            jdbc.update(
                "update agenda_google_event set state='SYNCED',synced_version=?,updated_at=now() where event_id=? and calendar_id=?",
                event.version(),
                event.id(),
                connection.calendar()
            );
            jdbc.update(
                "update agenda_sync_outbox set completed_at=now(),last_error=null where id=?",
                job.id()
            );
        } catch (GoogleCalendarGateway.Failure failure) {
            failed(job, connection, failure.code(), failure.retryable());
        } catch (IllegalStateException failure) {
            failed(job, connection, "REAUTH_REQUIRED", false);
        }
        return true;
    }

    /** Registra el fallo y aplica espera creciente con variación; los errores definitivos requieren intervención. */
    private void failed(Job job, Connection connection, String code, boolean retryable) {
        int attempts = job.attempts() + 1;
        if (!retryable) {
            attempts = 8;
        }
        long delay =
            Math.min(3600, 30L << Math.min(attempts, 7)) +
            java.util.concurrent.ThreadLocalRandom.current().nextLong(15);
        jdbc.update(
            "update agenda_sync_outbox set attempts=?,next_attempt_at=now()+(?*interval '1 second'),last_error=? where id=?",
            attempts,
            delay,
            code,
            job.id()
        );
        String state = "ERROR";
        if (code.equals("CONFLICT")) {
            state = "CONFLICT";
        }
        jdbc.update(
            "update agenda_google_event set state=?,updated_at=now() where event_id=? and calendar_id=?",
            state,
            job.eventId(),
            connection.calendar()
        );
        if (code.equals("REAUTH_REQUIRED") || code.equals("NO_PERMISSION")) {
            jdbc.update(
                "update google_calendar_connection set state=?,version=version+1,updated_at=now() where id=?",
                code,
                connection.id()
            );
        }
    }
}
