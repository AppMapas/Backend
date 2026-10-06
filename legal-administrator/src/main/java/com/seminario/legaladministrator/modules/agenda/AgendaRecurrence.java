package com.seminario.legaladministrator.modules.agenda;

import static com.seminario.legaladministrator.modules.agenda.AgendaDtos.*;

import com.seminario.legaladministrator.shared.OperationException;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.springframework.http.HttpStatus;

/** Expansión acotada: una serie y sus excepciones, sin persistir todas las ocurrencias. */
public final class AgendaRecurrence {

    private AgendaRecurrence() {}

    /** Expande una serie finita en su zona horaria; omite fechas inexistentes y horas que desaparecen al cambiar el horario estacional. */
    public static List<Instant> starts(Instant initial, String timeZone, Recurrence recurrence) {
        if (recurrence == null) {
            return List.of(initial);
        }
        ZoneId zone = ZoneId.of(timeZone);
        ZonedDateTime first = initial.atZone(zone);
        LocalDate firstDay = first.toLocalDate();
        if (
            recurrence.frequency() == null ||
            recurrence.until() == null ||
            recurrence.until().isBefore(firstDay) ||
            recurrence.until().isAfter(firstDay.plusDays(366))
        ) {
            throw new OperationException(
                HttpStatus.BAD_REQUEST,
                "La repetición requiere frecuencia y un fin dentro de los siguientes 366 días."
            );
        }
        List<Instant> result = new ArrayList<>();
        for (int index = 0; index <= 366; index++) {
            LocalDate day;
            switch (recurrence.frequency()) {
                case DAILY -> day = firstDay.plusDays(index);
                case WEEKLY -> day = firstDay.plusWeeks(index);
                case MONTHLY -> {
                    YearMonth month = YearMonth.from(firstDay).plusMonths(index);
                    if (firstDay.getDayOfMonth() > month.lengthOfMonth()) {
                        continue;
                    }
                    day = month.atDay(firstDay.getDayOfMonth());
                }
                case YEARLY -> {
                    int year = firstDay.getYear() + index;
                    MonthDay monthDay = MonthDay.from(firstDay);
                    if (!monthDay.isValidYear(year)) {
                        continue;
                    }
                    day = monthDay.atYear(year);
                }
                default -> throw new IllegalStateException("Frecuencia no soportada.");
            }
            if (day.isAfter(recurrence.until())) {
                break;
            }
            LocalDateTime candidate = day.atTime(first.toLocalTime());
            if (zone.getRules().getValidOffsets(candidate).isEmpty()) {
                continue;
            }
            if (index == 0) {
                result.add(initial);
            } else {
                result.add(candidate.atZone(zone).toInstant());
            }
        }
        return List.copyOf(result);
    }

    /** Mantiene la duración de actividades con hora y la cantidad de días locales de actividades de todo el día. */
    public static Instant end(
        Instant start,
        Instant baseStart,
        Instant baseEnd,
        String zone,
        boolean allDay
    ) {
        if (!allDay) {
            return start.plus(Duration.between(baseStart, baseEnd));
        }
        ZoneId timeZone = ZoneId.of(zone);
        long days = java.time.temporal.ChronoUnit.DAYS.between(
            baseStart.atZone(timeZone).toLocalDate(),
            baseEnd.atZone(timeZone).toLocalDate()
        );
        return start.atZone(timeZone).toLocalDate().plusDays(days).atStartOfDay(timeZone).toInstant();
    }

    /** Construye una ocurrencia a partir de la serie sin insertar un registro nuevo en la base de datos. */
    public static Event occurrence(Event master, Instant start) {
        Instant end = end(start, master.startsAt(), master.endsAt(), master.timeZone(), master.allDay());
        Instant original = null;
        if (master.recurrence() != null) {
            original = start;
        }
        return copy(
            master,
            master.title(),
            master.description(),
            start,
            end,
            master.location(),
            master.status(),
            original,
            master.googleEventId()
        );
    }

    public static Event copy(
        Event base,
        String title,
        String description,
        Instant start,
        Instant end,
        String location,
        String status,
        Instant original,
        String googleId
    ) {
        return new Event(
            base.id(),
            title,
            description,
            base.type(),
            start,
            end,
            base.timeZone(),
            base.allDay(),
            location,
            base.clientDpi(),
            base.clientName(),
            base.caseId(),
            base.caseCode(),
            base.responsibleDpi(),
            status,
            base.version(),
            base.syncState(),
            base.requestId(),
            base.requestHash(),
            base.createdBy(),
            base.recurrence(),
            original,
            googleId
        );
    }

    /** Genera la regla RRULE para Google; todo el día utiliza fecha y las actividades con hora utilizan un límite UTC. */
    public static List<String> rules(Recurrence recurrence, String zone) {
        return rules(recurrence, zone, false);
    }

    /** Genera la regla RRULE para Google; todo el día utiliza fecha y las actividades con hora utilizan un límite UTC. */
    public static List<String> rules(Recurrence recurrence, String zone, boolean allDay) {
        if (recurrence == null) {
            return List.of();
        }
        if (allDay) {
            return List.of(
                "RRULE:FREQ=" +
                    recurrence.frequency().name() +
                    ";UNTIL=" +
                    recurrence.until().format(DateTimeFormatter.BASIC_ISO_DATE)
            );
        }
        Instant until = recurrence
            .until()
            .plusDays(1)
            .atStartOfDay(ZoneId.of(zone))
            .minusSeconds(1)
            .toInstant();
        String formatted = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
            .withZone(ZoneOffset.UTC)
            .format(until);
        return List.of("RRULE:FREQ=" + recurrence.frequency().name() + ";UNTIL=" + formatted);
    }
}
