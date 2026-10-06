package com.seminario.legaladministrator.modules.agenda;

import static com.seminario.legaladministrator.modules.agenda.AgendaDtos.*;

import com.seminario.legaladministrator.config.security.OfficeAccess;
import com.seminario.legaladministrator.shared.InputRules;
import com.seminario.legaladministrator.shared.OperationException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

/** Consulta y edita eventos externos del calendario autorizado sin importarlos automáticamente como expedientes o actividades internas. */
@Service
@RequiredArgsConstructor
@PreAuthorize("hasAnyAuthority('Abogada','Administrador') and @officeAccess.allowed(authentication)")
public class GoogleAgendaService {

    private final GoogleConnectionService connections;
    private final GoogleCalendarGateway google;
    private final GoogleTokenCipher cipher;
    private final JdbcTemplate jdbc;
    private final AgendaRepository local;
    private final OfficeAccess access;
    // Acota llamadas externas simultáneas; no almacena secretos ni estado en el navegador.
    private final Semaphore requests = new Semaphore(8);
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    private record Window(long minute, int count) {}

    /** Limita las consultas externas por usuaria y minuto en esta instancia del backend. */
    private void rate(String actor) {
        long minute = System.currentTimeMillis() / 60000;
        windows.entrySet().removeIf(entry -> entry.getValue().minute() < minute);
        windows.compute(actor, (key, previous) -> {
            int count = 1;
            if (previous != null && previous.minute() == minute) {
                count = previous.count() + 1;
            }
            if (count > 60) {
                throw new OperationException(
                    HttpStatus.TOO_MANY_REQUESTS,
                    "Se alcanzó el límite de consultas de Google. Espera un minuto."
                );
            }
            return new Window(minute, count);
        });
    }

    /** Acota llamadas simultáneas y libera el permiso incluso cuando Google falla. */
    private <T> T call(Supplier<T> action) {
        rate(access.current().getDpi());
        if (!requests.tryAcquire()) {
            throw new OperationException(
                HttpStatus.TOO_MANY_REQUESTS,
                "Hay consultas en curso. Intenta nuevamente en unos segundos."
            );
        }
        try {
            return action.get();
        } catch (GoogleCalendarGateway.Failure failure) {
            throw connections.providerFailure(failure);
        } catch (IllegalStateException failure) {
            throw new OperationException(
                HttpStatus.CONFLICT,
                "Tu conexión de Google necesita una nueva autorización."
            );
        } finally {
            requests.release();
        }
    }

    private String token(GoogleConnectionService.Connection c) {
        String token = google.refresh(
            cipher.decrypt(c.encryptedToken(), c.owner(), c.subject(), c.keyVersion())
        );
        google.validateCalendar(token);
        return token;
    }

    private void id(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_-]{1,1024}")) {
            throw new OperationException(HttpStatus.BAD_REQUEST, "El identificador del evento no es válido.");
        }
    }

    @SuppressWarnings("unchecked")
    public GooglePage list(Instant from, Instant to, String pageToken) {
        AgendaRules.window(from, to);
        if (
            pageToken != null &&
            (pageToken.isBlank() ||
                pageToken.length() > 4096 ||
                pageToken.chars().anyMatch(Character::isISOControl))
        ) {
            throw new OperationException(HttpStatus.BAD_REQUEST, "La página solicitada no es válida.");
        }
        return call(() -> {
            var c = connections.connected(false);
            var response = google.listEvents(token(c), c.calendar(), from, to, pageToken);
            if (response == null || !(response.get("items") instanceof List<?> items)) {
                if (response != null && !response.containsKey("items")) {
                    return new GooglePage(List.of(), next(response), Instant.now());
                }
                throw new GoogleCalendarGateway.Failure("INVALID_RESPONSE");
            }
            if (items.size() > 250) {
                throw new GoogleCalendarGateway.Failure("INVALID_RESPONSE");
            }
            List<GoogleEvent> events = new ArrayList<>();
            for (Object item : items) {
                if (!(item instanceof Map<?, ?> raw)) {
                    throw new GoogleCalendarGateway.Failure("INVALID_RESPONSE");
                }
                Map<String, Object> event = (Map<String, Object>) raw;
                if ("cancelled".equals(event.get("status"))) {
                    continue;
                }
                events.add(dto(c.calendar(), event));
            }
            return new GooglePage(List.copyOf(events), next(response), Instant.now());
        });
    }

    private String next(Map<String, Object> response) {
        if (
            response.get("nextPageToken") instanceof String value &&
            !value.isBlank() &&
            value.length() <= 4096
        ) {
            return value;
        }
        return null;
    }

    public GoogleEvent get(String eventId) {
        id(eventId);
        return call(() -> {
            var c = connections.connected(false);
            return dto(c.calendar(), google.getEvent(token(c), c.calendar(), eventId));
        });
    }

    public GoogleEvent update(String eventId, GoogleEditRequest request) {
        id(eventId);
        if (request.scope() == null || !List.of("ONE", "ALL").contains(request.scope())) {
            throw new OperationException(
                HttpStatus.BAD_REQUEST,
                "Selecciona una ocurrencia o toda la serie."
            );
        }
        EventRequest validation = new EventRequest(
            request.title(),
            request.description(),
            ActivityType.FOLLOW_UP,
            request.startsAt(),
            request.endsAt(),
            request.timeZone(),
            request.allDay(),
            null,
            null,
            null,
            null,
            null,
            request.recurrence()
        );
        AgendaRules.validate(validation);
        String title = InputRules.required(request.title(), 150, "El título");
        if (request.description() != null && request.description().length() > 2000) {
            throw new OperationException(
                HttpStatus.BAD_REQUEST,
                "La descripción admite hasta 2000 caracteres."
            );
        }
        return call(() -> {
            var c = connections.connected(false);
            String token = token(c);
            Map<String, Object> current = google.getEvent(token, c.calendar(), eventId);
            GoogleEvent previous = dto(c.calendar(), current);
            if (previous.localEventId() != null) {
                throw new OperationException(
                    HttpStatus.CONFLICT,
                    "Edita esta actividad desde su registro interno para conservar el expediente y el historial."
                );
            }
            if (!previous.editable()) {
                throw new OperationException(
                    HttpStatus.CONFLICT,
                    "Este evento se consulta aquí y se edita desde Google Calendar."
                );
            }
            if (!previous.etag().equals(request.etag())) {
                throw new OperationException(
                    HttpStatus.CONFLICT,
                    "El evento cambió. Recarga antes de guardar."
                );
            }
            if (request.scope().equals("ALL") && previous.seriesId() != null) {
                throw new OperationException(
                    HttpStatus.BAD_REQUEST,
                    "Consulta primero el evento principal de la serie."
                );
            }
            if (
                request.scope().equals("ONE") &&
                (!previous.recurrence().isEmpty() || request.recurrence() != null)
            ) {
                throw new OperationException(
                    HttpStatus.BAD_REQUEST,
                    "La repetición se modifica en el evento principal."
                );
            }
            if (
                !request.startsAt().equals(previous.startsAt()) &&
                request.startsAt().isBefore(Instant.now().minusSeconds(60))
            ) {
                throw new OperationException(
                    HttpStatus.BAD_REQUEST,
                    "Reprograma para una fecha presente o futura."
                );
            }
            Map<String, Object> body = new HashMap<>(
                GoogleEventMapper.times(
                    new GoogleEventMapper.Schedule(
                        request.startsAt(),
                        request.endsAt(),
                        request.timeZone(),
                        request.allDay()
                    )
                )
            );
            body.put("summary", title);
            body.put("description", Objects.requireNonNullElse(request.description(), ""));
            if (request.clearRecurrence()) {
                if (!request.scope().equals("ALL") || request.recurrence() != null) {
                    throw new OperationException(
                        HttpStatus.BAD_REQUEST,
                        "La repetición se retira en toda la serie."
                    );
                }
                body.put("recurrence", List.of());
            }
            if (request.recurrence() != null) {
                body.put(
                    "recurrence",
                    AgendaRecurrence.rules(request.recurrence(), request.timeZone(), request.allDay())
                );
            }
            Map<String, Object> saved = google.patchEvent(token, c.calendar(), eventId, request.etag(), body);
            jdbc.update(
                "insert into agenda_google_audit(operator_dpi,calendar_id,google_event_id,action) values(?,?,?,'UPDATED')",
                c.owner(),
                c.calendar(),
                eventId
            );
            return dto(c.calendar(), saved);
        });
    }

    private GoogleEvent dto(String calendar, Map<String, Object> raw) {
        String eventId = GoogleCalendarGateway.required(raw, "id");
        id(eventId);
        var schedule = GoogleEventMapper.schedule(raw, "America/Guatemala");
        String series = null;
        if (raw.get("recurringEventId") instanceof String value) {
            id(value);
            series = value;
        }
        String lookup = eventId;
        if (series != null) {
            lookup = series;
        }
        List<Long> links = jdbc.query(
            "select event_id from agenda_google_event where calendar_id=? and google_event_id=?",
            (r, n) -> r.getLong(1),
            calendar,
            lookup
        );
        Long localId = null;
        Long caseId = null;
        String clientDpi = null;
        boolean conflict = false;
        Instant original = GoogleEventMapper.original(raw, schedule.zone());
        if (!links.isEmpty()) {
            localId = links.get(0);
            Event master = local.find(localId).orElseThrow();
            Event expected = master;
            caseId = master.caseId();
            clientDpi = master.clientDpi();
            if (original != null && master.recurrence() != null) {
                try {
                    expected = local.occurrence(master, original);
                } catch (OperationException exception) {
                    conflict = true;
                }
            }
            if (master.syncState().equals("SYNCED")) {
                conflict =
                    conflict ||
                    !expected.startsAt().equals(schedule.start()) ||
                    !expected.endsAt().equals(schedule.end()) ||
                    !Objects.equals(google.payload(expected).get("summary"), raw.get("summary"));
            }
        }
        boolean guests = GoogleCalendarGateway.hasGuests(raw);
        String type = Objects.toString(raw.get("eventType"), "default");
        boolean editable =
            !guests &&
            !Boolean.TRUE.equals(raw.get("locked")) &&
            type.equals("default") &&
            !"cancelled".equals(raw.get("status"));
        String url = "https://calendar.google.com/calendar/u/0/r";
        if (raw.get("htmlLink") instanceof String value) {
            try {
                var uri = java.net.URI.create(value);
                if (
                    "https".equals(uri.getScheme()) &&
                    "calendar.google.com".equals(uri.getHost()) &&
                    uri.getUserInfo() == null &&
                    uri.getPort() == -1
                ) {
                    url = value;
                }
            } catch (RuntimeException ignored) {
                /* Usar enlace fijo. */
            }
        }
        String title = GoogleEventMapper.text(raw, "summary", 150);
        if (title.isBlank()) {
            title = "Actividad de Google Calendar";
        }
        return new GoogleEvent(
            eventId,
            title,
            GoogleEventMapper.text(raw, "description", 2000),
            schedule.start(),
            schedule.end(),
            schedule.zone(),
            schedule.allDay(),
            Objects.toString(raw.get("status"), "confirmed"),
            GoogleCalendarGateway.required(raw, "etag"),
            series,
            original,
            GoogleEventMapper.rules(raw),
            editable,
            guests,
            localId,
            conflict,
            url,
            caseId,
            clientDpi
        );
    }
}
