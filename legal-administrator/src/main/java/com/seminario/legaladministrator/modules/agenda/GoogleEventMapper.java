package com.seminario.legaladministrator.modules.agenda;

import java.time.*;
import java.util.*;

/** Solo campos necesarios para Agenda; nunca serializa la respuesta cruda del proveedor. */
/** Convierte respuestas de Google en datos acotados para la interfaz, respetando fechas y enlaces permitidos. */
public final class GoogleEventMapper {

    private GoogleEventMapper() {}

    public record Schedule(Instant start, Instant end, String zone, boolean allDay) {}

    public static Map<?, ?> object(Map<String, Object> event, String key) {
        if (event.get(key) instanceof Map<?, ?> value) {
            return value;
        }
        throw new GoogleCalendarGateway.Failure("INVALID_RESPONSE");
    }

    public static Schedule schedule(Map<String, Object> event, String fallbackZone) {
        try {
            Map<?, ?> start = object(event, "start");
            Map<?, ?> end = object(event, "end");
            String zone = fallbackZone;
            if (start.get("timeZone") instanceof String value) {
                zone = value;
            }
            ZoneId timeZone = ZoneId.of(zone);
            boolean allDay = start.get("date") instanceof String;
            if (allDay) {
                return new Schedule(
                    LocalDate.parse((String) start.get("date"))
                        .atStartOfDay(timeZone)
                        .toInstant(),
                    LocalDate.parse((String) end.get("date"))
                        .atStartOfDay(timeZone)
                        .toInstant(),
                    zone,
                    true
                );
            }
            return new Schedule(
                Instant.parse((String) start.get("dateTime")),
                Instant.parse((String) end.get("dateTime")),
                zone,
                false
            );
        } catch (RuntimeException failure) {
            throw new GoogleCalendarGateway.Failure("INVALID_RESPONSE");
        }
    }

    public static Instant original(Map<String, Object> event, String zone) {
        if (!(event.get("originalStartTime") instanceof Map<?, ?> time)) {
            return null;
        }
        try {
            if (time.get("dateTime") instanceof String value) {
                return Instant.parse(value);
            }
            if (time.get("date") instanceof String date) {
                return LocalDate.parse(date).atStartOfDay(ZoneId.of(zone)).toInstant();
            }
        } catch (RuntimeException failure) {
            throw new GoogleCalendarGateway.Failure("INVALID_RESPONSE");
        }
        throw new GoogleCalendarGateway.Failure("INVALID_RESPONSE");
    }

    public static List<String> rules(Map<String, Object> event) {
        if (!(event.get("recurrence") instanceof List<?> values)) {
            return List.of();
        }
        if (values.size() > 20) {
            throw new GoogleCalendarGateway.Failure("INVALID_RESPONSE");
        }
        List<String> result = new ArrayList<>();
        for (Object value : values) {
            if (!(value instanceof String rule) || rule.length() > 2048) {
                throw new GoogleCalendarGateway.Failure("INVALID_RESPONSE");
            }
            result.add(rule);
        }
        return List.copyOf(result);
    }

    public static String text(Map<String, Object> event, String key, int max) {
        if (!(event.get(key) instanceof String value)) {
            return "";
        }
        if (value.length() > max) {
            return value.substring(0, max);
        }
        return value;
    }

    public static Map<String, Object> times(Schedule schedule) {
        Map<String, Object> start = new HashMap<>();
        Map<String, Object> end = new HashMap<>();
        if (schedule.allDay()) {
            ZoneId zone = ZoneId.of(schedule.zone());
            start.put("date", schedule.start().atZone(zone).toLocalDate().toString());
            start.put("dateTime", null);
            start.put("timeZone", null);
            end.put("date", schedule.end().atZone(zone).toLocalDate().toString());
            end.put("dateTime", null);
            end.put("timeZone", null);
        } else {
            start.put("dateTime", schedule.start().toString());
            start.put("timeZone", schedule.zone());
            start.put("date", null);
            end.put("dateTime", schedule.end().toString());
            end.put("timeZone", schedule.zone());
            end.put("date", null);
        }
        return Map.of("start", start, "end", end);
    }
}
