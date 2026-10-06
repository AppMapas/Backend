package com.seminario.legaladministrator.modules.agenda;

import static com.seminario.legaladministrator.modules.agenda.AgendaDtos.*;

import com.seminario.legaladministrator.config.security.OfficeAccess;
import com.seminario.legaladministrator.shared.OperationException;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Administra la autorización individual, la revocación y la reconciliación explícita entre horarios locales y externos. */
@Service
@RequiredArgsConstructor
@PreAuthorize("hasAnyAuthority('Abogada','Administrador') and @officeAccess.allowed(authentication)")
public class GoogleConnectionService {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final OfficeAccess access;
    private final GoogleCalendarProperties properties;
    private final GoogleCalendarGateway google;
    private final GoogleTokenCipher cipher;
    private final AgendaRepository events;
    private final AgendaService agenda;

    public record Connection(
        long id,
        String owner,
        String subject,
        String email,
        String calendar,
        String encryptedToken,
        String keyVersion,
        String state,
        long version
    ) {}

    /** Obtiene únicamente la conexión de la usuaria autenticada; puede bloquearla para coordinar cambios. */
    public Connection connection(boolean lock) {
        String actor = access.current().getDpi();
        if (lock) {
            jdbc.update(
                "insert into google_calendar_connection(owner_dpi,calendar_id) values(?,?) on conflict(owner_dpi) do nothing",
                actor,
                properties.getCalendarId()
            );
        }
        String sql = "select * from google_calendar_connection where owner_dpi=?";
        if (lock) {
            sql += " for update";
        }
        List<Connection> rows = jdbc.query(
            sql,
            (row, rowIndex) ->
                new Connection(
                    row.getLong("id"),
                    row.getString("owner_dpi"),
                    row.getString("google_subject"),
                    row.getString("google_email"),
                    row.getString("calendar_id"),
                    row.getString("encrypted_refresh_token"),
                    row.getString("key_version"),
                    row.getString("state"),
                    row.getLong("version")
                ),
            actor
        );
        if (rows.isEmpty()) {
            return new Connection(
                0,
                actor,
                null,
                null,
                properties.getCalendarId(),
                null,
                null,
                "DISCONNECTED",
                0
            );
        }
        return rows.get(0);
    }

    /** Entrega al frontend el estado y el ID público del cliente, sin exponer secretos ni tokens. */
    public GoogleStatus status() {
        Connection connection = connection(false);
        String clientId = "";
        if (properties.configured()) {
            clientId = properties.getClientId();
        }
        Long pending = jdbc.queryForObject(
            "select count(*) from agenda_sync_outbox where completed_at is null and connection_id=?",
            Long.class,
            connection.id()
        );
        return new GoogleStatus(
            properties.configured(),
            clientId,
            GoogleCalendarProperties.SCOPE,
            connection.state(),
            connection.email(),
            true,
            Objects.requireNonNull(pending)
        );
    }

    /** Genera un state aleatorio de un solo uso, guarda su huella y lo vincula a la usuaria durante diez minutos. */
    public OAuthIntent begin(String origin, String header) {
        guard(origin, header);
        String actor = access.current().getDpi();
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        String state = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        transactions.executeWithoutResult(tx -> {
            connection(true);
            jdbc.update(
                "delete from google_calendar_oauth_intent where owner_dpi=? or expires_at<now()",
                actor
            );
            jdbc.update(
                "insert into google_calendar_oauth_intent(state_hash,owner_dpi,expires_at) values(?,?,?)",
                hash(state),
                actor,
                Timestamp.from(Instant.now().plusSeconds(600))
            );
        });
        return new OAuthIntent(state);
    }

    /** Consume el state antes del canje; verifica identidad, permiso y calendario antes de cifrar la credencial. */
    public GoogleStatus connect(ConnectRequest request, String origin, String header) {
        guard(origin, header);
        String actor = access.current().getDpi();
        Long previousVersion = transactions.execute(tx -> {
            Connection previous = connection(true);
            int consumed = jdbc.update(
                "delete from google_calendar_oauth_intent where state_hash=? and owner_dpi=? and expires_at>now()",
                hash(request.state()),
                actor
            );
            if (consumed != 1) {
                conflict("La autorización venció o ya fue utilizada. Inicia la conexión nuevamente.");
            }
            return previous.version();
        });
        try {
            Map<String, Object> token = google.exchange(request.code());
            String scopes = GoogleCalendarGateway.required(token, "scope");
            if (
                !Arrays.asList(scopes.split(" ")).contains("https://www.googleapis.com/auth/calendar.events")
            ) {
                conflict("Autoriza el permiso de calendario.");
            }
            String accessToken = GoogleCalendarGateway.required(token, "access_token");
            Map<String, Object> identity = google.identity(accessToken);
            String subject = GoogleCalendarGateway.required(identity, "sub");
            String email = GoogleCalendarGateway.required(identity, "email");
            if (!Boolean.TRUE.equals(identity.get("email_verified"))) {
                conflict("La cuenta debe tener correo verificado.");
            }
            if (subject.length() > 255 || email.length() > 255) {
                conflict("La identidad de Google no es válida.");
            }
            google.validateCalendar(accessToken);
            transactions.executeWithoutResult(tx -> {
                Connection previous = connection(true);
                jdbc.queryForObject(
                    "select pg_advisory_xact_lock(hashtextextended(?,1200712))",
                    (row, index) -> true,
                    subject
                );
                Boolean used = jdbc.queryForObject(
                    "select exists(select 1 from google_calendar_connection where google_subject=? and owner_dpi<>? and state<>'DISCONNECTED')",
                    Boolean.class,
                    subject,
                    actor
                );
                if (Boolean.TRUE.equals(used)) {
                    conflict("Esta cuenta de Google ya está conectada a otro usuario interno.");
                }
                if (previous.version() != Objects.requireNonNull(previousVersion)) {
                    conflict("La conexión cambió durante la autorización. Vuelve a conectar.");
                }
                if (previous.calendar() != null && !previous.calendar().equals(properties.getCalendarId())) {
                    conflict("Concilia la conexión antes de cambiar el calendario destino.");
                }
                if (
                    !previous.state().equals("DISCONNECTED") &&
                    previous.subject() != null &&
                    !previous.subject().equals(subject)
                ) {
                    conflict("Desconecta la cuenta anterior antes de elegir otra.");
                }
                String refresh;
                if (token.get("refresh_token") instanceof String value && !value.isBlank()) {
                    refresh = value;
                } else if (subject.equals(previous.subject()) && previous.encryptedToken() != null) {
                    refresh = cipher.decrypt(
                        previous.encryptedToken(),
                        actor,
                        subject,
                        previous.keyVersion()
                    );
                } else {
                    conflict(
                        "Google no entregó una credencial renovable. Retira el permiso de la aplicación en Google y vuelve a conectar."
                    );
                    return;
                }
                jdbc.update(
                    """
                    update google_calendar_connection set google_subject=?,google_email=?,calendar_id=?,encrypted_refresh_token=?,key_version=?,
                      state='CONNECTED',version=version+1,updated_at=now() where owner_dpi=?
                    """,
                    subject,
                    email,
                    properties.getCalendarId(),
                    cipher.encrypt(refresh, actor, subject),
                    properties.getKeyVersion(),
                    actor
                );
                assignOwnWaiting(previous.id(), actor);
            });
            return status();
        } catch (GoogleCalendarGateway.Failure failure) {
            throw providerFailure(failure);
        } catch (IllegalStateException exception) {
            conflict("La credencial necesita reconexión. Revisa la configuración.");
            return null;
        }
    }

    /** Asigna a esta conexión los trabajos pendientes originados por su propia titular. */
    private void assignOwnWaiting(long connectionId, String actor) {
        jdbc.update(
            """
            update agenda_sync_outbox job set connection_id=?,attempts=0,next_attempt_at=now(),last_error=null
            where completed_at is null and connection_id is null and exists(
                select 1 from agenda_event_history history where history.event_id=job.event_id and history.event_version=job.event_version and history.operator_dpi=?)
            """,
            connectionId,
            actor
        );
    }

    /** Intenta revocar la credencial en Google y elimina el token local; conserva actividades e historial. */
    public GoogleStatus disconnect() {
        transactions.executeWithoutResult(tx -> {
            Connection connection = connection(true);
            if (connection.encryptedToken() != null) {
                try {
                    google.revoke(
                        cipher.decrypt(
                            connection.encryptedToken(),
                            connection.owner(),
                            connection.subject(),
                            connection.keyVersion()
                        )
                    );
                } catch (GoogleCalendarGateway.Failure | IllegalStateException exception) {
                    /* Siempre retirar el acceso local. */
                }
            }
            jdbc.update(
                """
                update google_calendar_connection set state='DISCONNECTED',encrypted_refresh_token=null,google_subject=null,google_email=null,
                  key_version=null,version=version+1,updated_at=now() where id=?
                """,
                connection.id()
            );
            jdbc.update("delete from google_calendar_oauth_intent where owner_dpi=?", connection.owner());
        });
        return status();
    }

    public GoogleStatus retry() {
        transactions.executeWithoutResult(tx -> {
            Connection connection = connected(true);
            assignOwnWaiting(connection.id(), connection.owner());
            jdbc.update(
                """
                update agenda_sync_outbox set attempts=0,next_attempt_at=now(),last_error=null where connection_id=? and completed_at is null
                and (last_error is null or last_error not in ('CONFLICT','NO_PERMISSION','REAUTH_REQUIRED','UNSUPPORTED_GUESTS'))
                """,
                connection.id()
            );
        });
        return status();
    }

    /** Reasigna de forma explícita un trabajo pendiente a la conexión de la operadora actual. */
    public Event assign(long id, long version) {
        transactions.executeWithoutResult(tx -> {
            Connection connection = connected(true);
            events.lockWrites();
            Event event = events
                .find(id)
                .orElseThrow(() -> new OperationException(HttpStatus.NOT_FOUND, "La actividad no existe."));
            if (event.version() != version) {
                conflict("La actividad cambió. Recarga sus datos.");
            }
            int assigned = jdbc.update(
                """
                update agenda_sync_outbox set connection_id=?,attempts=0,next_attempt_at=now(),last_error=null
                where event_id=? and event_version=? and completed_at is null
                and (last_error is null or last_error not in ('CONFLICT','UNSUPPORTED_GUESTS'))
                """,
                connection.id(),
                id,
                version
            );
            if (assigned == 0) {
                conflict("No hay un trabajo pendiente recuperable. Revisa la sincronización.");
            }
            jdbc.update(
                "update legal_process_calendar set version=version+1,updated_at=now() where id=?",
                id
            );
            events.changed(
                events.find(id).orElseThrow(),
                "GOOGLE_ASSIGNED",
                connection.owner(),
                "Se reasignó explícitamente la publicación a la conexión del operador."
            );
        });
        return events.find(id).orElseThrow();
    }

    public Connection connected(boolean lock) {
        Connection connection = connection(lock);
        if (
            !properties.configured() ||
            !connection.state().equals("CONNECTED") ||
            !properties.getCalendarId().equals(connection.calendar())
        ) {
            conflict("Conecta tu cuenta de Google para usar el calendario compartido.");
        }
        return connection;
    }

    /** Resuelve un conflicto con una elección explícita de horario y el ETag vigente de Google. */
    public Event reconcile(long id, ReconcileRequest request) {
        try {
            transactions.executeWithoutResult(tx -> {
                Connection connection = connected(true);
                events.lockWrites();
                Event event = events
                    .find(id)
                    .orElseThrow(() ->
                        new OperationException(HttpStatus.NOT_FOUND, "La actividad no existe.")
                    );
                if (request.version() == null || event.version() != request.version()) {
                    conflict("La actividad cambió. Recarga sus datos.");
                }
                List<String> links = jdbc.query(
                    "select google_event_id from agenda_google_event where event_id=? and calendar_id=?",
                    (row, rowIndex) -> row.getString(1),
                    id,
                    connection.calendar()
                );
                if (links.isEmpty()) {
                    conflict("La actividad aún no tiene vínculo con Google.");
                }
                String token = google.refresh(
                    cipher.decrypt(
                        connection.encryptedToken(),
                        connection.owner(),
                        connection.subject(),
                        connection.keyVersion()
                    )
                );
                String externalId = links.get(0);
                if (request.originalStartsAt() != null) {
                    reconcileOccurrence(event, request, token, connection, externalId);
                    return;
                }
                String etag = null;
                if (request.useAppSchedule()) {
                    Optional<String> current = google.currentEtag(
                        token,
                        connection.calendar(),
                        externalId,
                        id
                    );
                    if (current.isPresent()) {
                        etag = current.get();
                    } else {
                        externalId = "legal" + UUID.randomUUID().toString().replace("-", "");
                        jdbc.update(
                            "update legal_process_calendar set master_revision=master_revision+1 where id=?",
                            id
                        );
                        jdbc.update(
                            "update agenda_event_exception set google_event_id=null,etag=null,synced_revision=-1 where event_id=?",
                            id
                        );
                    }
                } else {
                    Map<String, Object> remote = google.getEvent(token, connection.calendar(), externalId);
                    if (GoogleCalendarGateway.hasGuests(remote)) {
                        conflict("Este evento tiene invitados. Revísalo desde Google Calendar.");
                    }
                    var schedule = GoogleEventMapper.schedule(remote, event.timeZone());
                    if (
                        !GoogleEventMapper.rules(remote).equals(
                            AgendaRecurrence.rules(event.recurrence(), event.timeZone(), event.allDay())
                        )
                    ) {
                        conflict("La repetición externa difiere. Concilia primero la serie desde Google.");
                    }
                    EventRequest change = new EventRequest(
                        event.title(),
                        event.description(),
                        ActivityType.valueOf(event.type()),
                        schedule.start(),
                        schedule.end(),
                        schedule.zone(),
                        schedule.allDay(),
                        event.location(),
                        event.clientDpi(),
                        event.caseId(),
                        null,
                        event.version(),
                        event.recurrence()
                    );
                    agenda.update(id, change);
                    etag = GoogleCalendarGateway.required(remote, "etag");
                }
                jdbc.update(
                    "update agenda_google_event set google_event_id=?,etag=?,state='PENDING',synced_master_revision=-1,updated_at=now() where event_id=? and calendar_id=?",
                    externalId,
                    etag,
                    id,
                    connection.calendar()
                );
                jdbc.update(
                    "update legal_process_calendar set version=version+1,updated_at=now() where id=?",
                    id
                );
                String reason = "Se confirmó conservar el horario de la aplicación.";
                if (!request.useAppSchedule()) {
                    reason = "Se aplicó el horario de Google a la actividad interna.";
                }
                events.changed(
                    events.find(id).orElseThrow(),
                    "GOOGLE_RECONCILED",
                    connection.owner(),
                    reason
                );
            });
            Event saved = events.find(id).orElseThrow();
            if (request.originalStartsAt() != null) {
                return events.occurrence(saved, request.originalStartsAt());
            }
            return saved;
        } catch (GoogleCalendarGateway.Failure failure) {
            throw providerFailure(failure);
        } catch (IllegalStateException exception) {
            conflict("La credencial requiere reconexión.");
            return null;
        }
    }

    /** Resuelve una discrepancia de una sola ocurrencia conservando la identidad original dentro de la serie. */
    private void reconcileOccurrence(
        Event master,
        ReconcileRequest request,
        String token,
        Connection connection,
        String seriesId
    ) {
        Event occurrence = events.occurrence(master, request.originalStartsAt());
        Map<String, Object> remote = google.instance(
            token,
            connection.calendar(),
            seriesId,
            request.originalStartsAt(),
            master.timeZone()
        );
        if (GoogleCalendarGateway.hasGuests(remote)) {
            conflict("Este evento tiene invitados. Revísalo desde Google Calendar.");
        }
        EventRequest change = new EventRequest(
            occurrence.title(),
            occurrence.description(),
            ActivityType.valueOf(occurrence.type()),
            occurrence.startsAt(),
            occurrence.endsAt(),
            occurrence.timeZone(),
            occurrence.allDay(),
            occurrence.location(),
            occurrence.clientDpi(),
            occurrence.caseId(),
            null,
            master.version()
        );
        if (request.useAppSchedule()) {
            events.saveException(master, request.originalStartsAt(), change, occurrence.status());
        } else {
            var schedule = GoogleEventMapper.schedule(remote, master.timeZone());
            change = new EventRequest(
                occurrence.title(),
                occurrence.description(),
                ActivityType.valueOf(occurrence.type()),
                schedule.start(),
                schedule.end(),
                master.timeZone(),
                master.allDay(),
                occurrence.location(),
                occurrence.clientDpi(),
                occurrence.caseId(),
                null,
                master.version()
            );
            agenda.updateOccurrence(master.id(), new OccurrenceRequest(request.originalStartsAt(), change));
        }
        jdbc.update(
            "update agenda_event_exception set google_event_id=?,etag=?,synced_revision=-1 where event_id=? and original_starts_at=?",
            GoogleCalendarGateway.required(remote, "id"),
            GoogleCalendarGateway.required(remote, "etag"),
            master.id(),
            Timestamp.from(request.originalStartsAt())
        );
        jdbc.update(
            "update legal_process_calendar set version=version+1,updated_at=now() where id=?",
            master.id()
        );
        String reason = "Se confirmó conservar el horario interno de la ocurrencia.";
        if (!request.useAppSchedule()) {
            reason = "Se aplicó el horario externo a una ocurrencia.";
        }
        events.changed(
            events.occurrence(events.find(master.id()).orElseThrow(), request.originalStartsAt()),
            "GOOGLE_RECONCILED",
            connection.owner(),
            reason
        );
    }

    /** Traduce fallos de Google a respuestas controladas sin devolver tokens ni mensajes crudos del proveedor. */
    public OperationException providerFailure(GoogleCalendarGateway.Failure failure) {
        if (failure.code().equals("REAUTH_REQUIRED") || failure.code().equals("NO_PERMISSION")) {
            jdbc.update(
                "update google_calendar_connection set state=?,version=version+1,updated_at=now() where owner_dpi=?",
                failure.code(),
                access.current().getDpi()
            );
            return new OperationException(
                HttpStatus.CONFLICT,
                "Revisa los permisos del calendario y vuelve a autorizar tu cuenta."
            );
        }
        if (failure.retryable()) {
            return new OperationException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "Google no está disponible. Tu agenda interna sigue disponible."
            );
        }
        if (failure.code().equals("CONFLICT")) {
            return new OperationException(
                HttpStatus.CONFLICT,
                "El evento cambió en Google. Recarga sus datos antes de editar."
            );
        }
        if (failure.code().equals("MISSING_EVENT")) {
            return new OperationException(
                HttpStatus.NOT_FOUND,
                "El evento fue eliminado en Google. Recarga la agenda."
            );
        }
        return new OperationException(
            HttpStatus.CONFLICT,
            "No se pudo completar la operación. Revisa el evento en Google."
        );
    }

    /** Exige configuración válida, origen exacto y encabezado de solicitud para iniciar o completar OAuth. */
    private void guard(String origin, String header) {
        if (!properties.configured()) {
            conflict("Google Calendar no está configurado. Puedes utilizar la agenda interna.");
        }
        if (!properties.getFrontendOrigin().equals(origin) || !"XmlHttpRequest".equals(header)) {
            throw new OperationException(HttpStatus.FORBIDDEN, "El origen de autorización no es válido.");
        }
    }

    static String hash(String value) {
        try {
            return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 no disponible.");
        }
    }

    private void conflict(String message) {
        throw new OperationException(HttpStatus.CONFLICT, message);
    }
}
