package com.seminario.legaladministrator.modules.agenda;

import static com.seminario.legaladministrator.modules.agenda.AgendaDtos.*;

import com.seminario.legaladministrator.config.security.OfficeAccess;
import com.seminario.legaladministrator.modules.processes.repository.LegalProcessRepository;
import com.seminario.legaladministrator.modules.processes.service.CaseRequestGuard;
import com.seminario.legaladministrator.modules.users.repository.ClientUserRepository;
import com.seminario.legaladministrator.shared.*;
import java.time.Instant;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Coordina las actividades internas dentro de una transacción: valida vínculos y horarios, registra historial y encola publicación. */
@Service
@RequiredArgsConstructor
@Transactional
@PreAuthorize("hasAnyAuthority('Abogada','Administrador') and @officeAccess.allowed(authentication)")
public class AgendaService {

    private final AgendaRepository events;
    private final OfficeAccess access;
    private final LegalProcessRepository cases;
    private final ClientUserRepository clients;
    private final CaseRequestGuard requests;

    /** Reutiliza una solicitud idéntica para evitar duplicados; una nueva actividad guarda historial y trabajo de sincronización. */
    public Creation create(EventRequest raw) {
        String actor = access.current().getDpi();
        EventRequest request = normalized(raw);
        AgendaRules.validate(request);
        if (request.requestId() == null) {
            bad("La solicitud necesita un identificador único.");
        }
        events.lockWrites();
        String hash = fingerprint(request);
        Optional<Event> previous = events.findRequest(request.requestId());
        if (previous.isPresent()) {
            Event saved = previous.get();
            if (!actor.equals(saved.createdBy()) || !hash.equals(saved.requestHash())) {
                conflict("La solicitud ya se usó con otros datos.");
            }
            return new Creation(saved, true);
        }
        if (request.startsAt().isBefore(Instant.now().minusSeconds(60))) {
            bad("Programa la actividad en una fecha presente o futura.");
        }
        String client = validateLinks(request);
        if (events.overlap(request, actor, -1)) {
            conflict("El horario coincide con otra actividad. Selecciona otro horario.");
        }
        long id = events.insert(request, client, actor, actor, hash);
        Event saved = get(id);
        events.changed(saved, "CREATED", actor, null);
        return new Creation(get(id), false);
    }

    /** Comprueba la versión y conserva las fechas originales de las excepciones antes de modificar una serie. */
    public Event update(long id, EventRequest raw) {
        String actor = access.current().getDpi();
        EventRequest request = normalized(raw);
        AgendaRules.validate(request);
        events.lockWrites();
        Event previous = get(id);
        version(previous, request.version());
        if (!previous.status().equals("SCHEDULED")) {
            conflict("La actividad ya terminó o fue cancelada y conserva su historial.");
        }
        if (
            request.startsAt().isBefore(Instant.now().minusSeconds(60)) &&
            !(
                previous.recurrence() != null &&
                request.startsAt().equals(previous.startsAt()) &&
                request.endsAt().equals(previous.endsAt())
            )
        ) {
            bad("Reprograma la actividad para una fecha presente o futura.");
        }
        var validStarts = AgendaRecurrence.starts(
            request.startsAt(),
            request.timeZone(),
            request.recurrence()
        );
        for (var exception : events.exceptions(id)) {
            if (!validStarts.contains(exception.original())) {
                conflict("La serie tiene excepciones. Conserva sus fechas o edita una ocurrencia.");
            }
        }
        String client = validateLinks(request);
        if (events.overlap(request, previous.responsibleDpi(), id)) {
            conflict("El horario coincide con otra actividad. Selecciona otro horario.");
        }
        events.update(id, request, client);
        events.changed(get(id), "UPDATED", actor, null);
        return get(id);
    }

    /** Completa o cancela una actividad programada; la cancelación requiere motivo y una serie se completa por ocurrencia. */
    public Event changeStatus(long id, StatusRequest request) {
        String actor = access.current().getDpi();
        events.lockWrites();
        Event event = get(id);
        version(event, request.version());
        if (event.recurrence() != null && request.status() == EventStatus.COMPLETED) {
            bad("Selecciona la ocurrencia que deseas marcar como realizada.");
        }
        AgendaRules.transition(EventStatus.valueOf(event.status()), request.status());
        String reason = InputRules.text(request.reason());
        if (request.status() == EventStatus.CANCELLED) {
            reason = InputRules.required(reason, 500, "El motivo de cancelación");
        }
        if (request.status() == EventStatus.COMPLETED && event.startsAt().isAfter(Instant.now())) {
            bad("La actividad aún no ha comenzado.");
        }
        events.status(id, request.status());
        events.changed(get(id), request.status().name(), actor, reason);
        return get(id);
    }

    @Transactional(readOnly = true)
    public Event occurrence(long id, Instant original) {
        access.current();
        return events.occurrence(get(id), original);
    }

    /** Modifica una sola fecha de la serie sin cambiar su cliente, expediente, tipo ni zona horaria. */
    public Event updateOccurrence(long id, OccurrenceRequest raw) {
        String actor = access.current().getDpi();
        events.lockWrites();
        Event master = get(id);
        Event current = events.occurrence(master, raw.originalStartsAt());
        EventRequest request = normalized(raw.event());
        version(master, request.version());
        if (!current.status().equals("SCHEDULED")) {
            conflict("Esta ocurrencia terminó o fue cancelada.");
        }
        AgendaRules.validate(request);
        if (
            request.recurrence() != null ||
            !Objects.equals(master.caseId(), request.caseId()) ||
            !Objects.equals(master.clientDpi(), validateLinks(request)) ||
            !master.type().equals(request.type().name()) ||
            !master.timeZone().equals(request.timeZone()) ||
            master.allDay() != request.allDay()
        ) {
            bad("Una ocurrencia conserva el tipo, cliente, expediente, zona y modalidad de su serie.");
        }
        if (request.startsAt().isBefore(Instant.now().minusSeconds(60))) {
            bad("Reprograma la ocurrencia para una fecha presente o futura.");
        }
        if (request.startsAt().isAfter(master.startsAt().plusSeconds(397L * 86400))) {
            bad("La ocurrencia excede el horizonte permitido.");
        }
        if (events.overlap(request, master.responsibleDpi(), id, raw.originalStartsAt())) {
            conflict("La ocurrencia coincide con otra actividad.");
        }
        events.saveException(master, raw.originalStartsAt(), request, current.status());
        Event saved = events.occurrence(get(id), raw.originalStartsAt());
        events.changed(saved, "OCCURRENCE_UPDATED", actor, null);
        return saved;
    }

    /** Guarda el estado excepcional de una ocurrencia y registra quién realizó la operación. */
    public Event changeOccurrenceStatus(long id, OccurrenceStatusRequest raw) {
        String actor = access.current().getDpi();
        events.lockWrites();
        Event master = get(id);
        Event current = events.occurrence(master, raw.originalStartsAt());
        StatusRequest request = raw.change();
        version(master, request.version());
        AgendaRules.transition(EventStatus.valueOf(current.status()), request.status());
        String reason = InputRules.text(request.reason());
        if (request.status() == EventStatus.CANCELLED) {
            reason = InputRules.required(reason, 500, "El motivo de cancelación");
        }
        if (request.status() == EventStatus.COMPLETED && current.startsAt().isAfter(Instant.now())) {
            bad("La ocurrencia aún no ha comenzado.");
        }
        EventRequest change = new EventRequest(
            current.title(),
            current.description(),
            ActivityType.valueOf(current.type()),
            current.startsAt(),
            current.endsAt(),
            current.timeZone(),
            current.allDay(),
            current.location(),
            current.clientDpi(),
            current.caseId(),
            null,
            current.version()
        );
        events.saveException(master, raw.originalStartsAt(), change, request.status().name());
        Event saved = events.occurrence(get(id), raw.originalStartsAt());
        events.changed(saved, "OCCURRENCE_" + request.status().name(), actor, reason);
        return saved;
    }

    @Transactional(readOnly = true)
    public Event get(long id) {
        return events
            .find(id)
            .orElseThrow(() -> new OperationException(HttpStatus.NOT_FOUND, "La actividad no existe."));
    }

    @Transactional(readOnly = true)
    public PageResponse<Event> search(
        Instant from,
        Instant to,
        Long caseId,
        String clientDpi,
        String status,
        int page,
        int size
    ) {
        AgendaRules.window(from, to);
        InputRules.page(page, size, Sort.unsorted());
        if (clientDpi != null) {
            InputRules.dpi(clientDpi);
        }
        if (status != null) {
            try {
                EventStatus.valueOf(status);
            } catch (IllegalArgumentException exception) {
                bad("El estado solicitado no es válido.");
            }
        }
        return events.search(from, to, caseId, clientDpi, status, page, size);
    }

    @Transactional(readOnly = true)
    public List<Event> calendar(Instant from, Instant to, Long caseId, String clientDpi, String status) {
        access.current();
        AgendaRules.window(from, to);
        if (clientDpi != null) {
            InputRules.dpi(clientDpi);
        }
        if (status != null) {
            try {
                EventStatus.valueOf(status);
            } catch (IllegalArgumentException exception) {
                bad("El estado solicitado no es válido.");
            }
        }
        return events.expanded(from, to, caseId, clientDpi, status);
    }

    @Transactional(readOnly = true)
    public List<DayCount> days(Instant from, Instant to, Long caseId, String clientDpi, String status) {
        AgendaRules.window(from, to);
        if (clientDpi != null) {
            InputRules.dpi(clientDpi);
        }
        if (status != null) {
            try {
                EventStatus.valueOf(status);
            } catch (IllegalArgumentException exception) {
                bad("El estado solicitado no es válido.");
            }
        }
        return events.days(from, to, caseId, clientDpi, status);
    }

    @Transactional(readOnly = true)
    public List<History> history(long id) {
        get(id);
        return events.history(id);
    }

    /** Verifica que cliente y expediente existan, estén activos y correspondan entre sí. */
    private String validateLinks(EventRequest request) {
        String dpi = request.clientDpi();
        if (request.caseId() != null) {
            var legalCase = cases
                .findById(request.caseId())
                .orElseThrow(() -> new OperationException(HttpStatus.NOT_FOUND, "El expediente no existe."));
            if (!legalCase.isActive()) {
                conflict("Selecciona un expediente activo.");
            }
            if (dpi != null && !dpi.equals(legalCase.getClient().getDpi())) {
                bad("El cliente no corresponde al expediente.");
            }
            dpi = legalCase.getClient().getDpi();
        }
        if (dpi != null) {
            var client = clients
                .findById(InputRules.dpi(dpi))
                .orElseThrow(() -> new OperationException(HttpStatus.NOT_FOUND, "El cliente no existe."));
            if (!client.isActive()) {
                conflict("Selecciona un cliente activo.");
            }
        }
        if (request.type() == ActivityType.APPOINTMENT && dpi == null) {
            bad("Selecciona el cliente de la cita.");
        }
        return dpi;
    }

    /** Limpia y limita textos antes de validar o calcular la huella de la solicitud. */
    private EventRequest normalized(EventRequest request) {
        String description = InputRules.text(request.description());
        if (description != null && description.length() > 2000) {
            bad("Las notas admiten hasta 2000 caracteres.");
        }
        String location = InputRules.text(request.location());
        if (location != null && location.length() > 255) {
            bad("El lugar admite hasta 255 caracteres.");
        }
        return new EventRequest(
            InputRules.required(request.title(), 150, "El título"),
            description,
            request.type(),
            request.startsAt(),
            request.endsAt(),
            request.timeZone(),
            request.allDay(),
            location,
            request.clientDpi(),
            request.caseId(),
            request.requestId(),
            request.version(),
            request.recurrence()
        );
    }

    /** Identifica el contenido de una solicitud para detectar reintentos y rechazar el mismo identificador con datos distintos. */
    private String fingerprint(EventRequest request) {
        return requests.fingerprint(
            Arrays.asList(
                request.title(),
                request.description(),
                request.type(),
                request.startsAt(),
                request.endsAt(),
                request.timeZone(),
                request.allDay(),
                request.location(),
                request.clientDpi(),
                request.caseId(),
                request.recurrence()
            )
        );
    }

    /** Rechaza una edición basada en datos anteriores para evitar sobrescribir cambios de otra operadora. */
    private void version(Event event, Long expectedVersion) {
        if (expectedVersion == null || expectedVersion != event.version()) {
            conflict("La actividad cambió. Recarga sus datos antes de guardar.");
        }
    }

    private void bad(String message) {
        throw new OperationException(HttpStatus.BAD_REQUEST, message);
    }

    private void conflict(String message) {
        throw new OperationException(HttpStatus.CONFLICT, message);
    }
}
