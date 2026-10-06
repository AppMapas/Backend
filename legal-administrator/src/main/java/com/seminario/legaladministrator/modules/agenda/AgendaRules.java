package com.seminario.legaladministrator.modules.agenda;

import static com.seminario.legaladministrator.modules.agenda.AgendaDtos.*;

import com.seminario.legaladministrator.shared.InputRules;
import com.seminario.legaladministrator.shared.OperationException;
import java.time.*;
import org.springframework.http.HttpStatus;

/** Agrupa las reglas de fechas, períodos de consulta y estados, compartidas por las operaciones de Agenda. */
public final class AgendaRules {

    private AgendaRules() {}

    public static void validate(EventRequest request) {
        InputRules.required(request.title(), 150, "El título");
        if (request.type() == null || request.startsAt() == null || request.endsAt() == null) {
            bad("Indica tipo, fecha de inicio y fecha de fin.");
        }
        ZoneId zone;
        try {
            zone = ZoneId.of(request.timeZone());
        } catch (RuntimeException exception) {
            bad("La zona horaria no es válida.");
            return;
        }
        if (
            !request.endsAt().isAfter(request.startsAt()) ||
            Duration.between(request.startsAt(), request.endsAt()).compareTo(Duration.ofDays(31)) > 0
        ) {
            bad("El fin debe ser posterior al inicio y la duración no puede superar 31 días.");
        }
        if (
            request.allDay() &&
            (!request.startsAt().atZone(zone).toLocalTime().equals(LocalTime.MIDNIGHT) ||
                !request.endsAt().atZone(zone).toLocalTime().equals(LocalTime.MIDNIGHT))
        ) {
            bad("Las actividades de todo el día deben iniciar y terminar a medianoche en su zona horaria.");
        }
        var occurrences = AgendaRecurrence.starts(
            request.startsAt(),
            request.timeZone(),
            request.recurrence()
        );
        Instant previousEnd = null;
        for (Instant start : occurrences) {
            if (previousEnd != null && start.isBefore(previousEnd)) {
                bad("Las ocurrencias de la serie se superponen entre sí.");
            }
            previousEnd = AgendaRecurrence.end(
                start,
                request.startsAt(),
                request.endsAt(),
                request.timeZone(),
                request.allDay()
            );
        }
        if (request.type() == ActivityType.PAYMENT_REMINDER || request.type() == ActivityType.HEARING) {
            if (request.caseId() == null) {
                bad("Este tipo de actividad requiere un expediente.");
            }
        }
    }

    public static void window(Instant from, Instant to) {
        if (
            from == null ||
            to == null ||
            !to.isAfter(from) ||
            Duration.between(from, to).compareTo(Duration.ofDays(93)) > 0
        ) {
            bad("Consulta un período válido de hasta 93 días.");
        }
    }

    public static void transition(EventStatus current, EventStatus target) {
        if (current != EventStatus.SCHEDULED || target == EventStatus.SCHEDULED) {
            throw new OperationException(
                HttpStatus.CONFLICT,
                "Solo una actividad programada puede completarse o cancelarse."
            );
        }
    }

    private static void bad(String message) {
        throw new OperationException(HttpStatus.BAD_REQUEST, message);
    }
}
