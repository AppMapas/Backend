package com.seminario.legaladministrator.modules.agenda;

import static com.seminario.legaladministrator.modules.agenda.AgendaDtos.*;

import com.seminario.legaladministrator.shared.OperationException;
import com.seminario.legaladministrator.shared.PageResponse;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** Persiste actividades, excepciones e historial mediante consultas parametrizadas. La serie se almacena una sola vez. */
@Repository
@RequiredArgsConstructor
public class AgendaRepository {

    private final JdbcTemplate jdbc;

    /** Serializa escrituras de Agenda en esta transacción para comprobar disponibilidad sin carreras entre solicitudes. */
    public void lockWrites() {
        jdbc.queryForObject("select pg_advisory_xact_lock(1100711)", (row, index) -> true);
    }

    private static final String SELECT = """
    select a.*, c.first_name || ' ' || c.last_name as client_name, p.case_code,
        case when g.state='SYNCED' and a.version>g.synced_version then 'PENDING' else coalesce(g.state,'LOCAL') end as sync_state,
        g.google_event_id
    from legal_process_calendar a
    left join client_user c on c.dpi=a.client_dpi
    left join legal_process p on p.id=a.id_legal_process
    left join lateral (select * from agenda_google_event where event_id=a.id order by updated_at desc limit 1) g on true
    """;
    private static final RowMapper<Event> MAPPER = (row, index) -> {
        Recurrence recurrence = null;
        if (row.getString("recurrence_frequency") != null) {
            recurrence = new Recurrence(
                Frequency.valueOf(row.getString("recurrence_frequency")),
                row.getDate("recurrence_until").toLocalDate()
            );
        }
        return new Event(
            row.getLong("id"),
            row.getString("event_title"),
            row.getString("event_description"),
            row.getString("activity_type"),
            row.getTimestamp("starts_at").toInstant(),
            row.getTimestamp("ends_at").toInstant(),
            row.getString("time_zone"),
            row.getBoolean("all_day"),
            row.getString("location"),
            row.getString("client_dpi"),
            row.getString("client_name"),
            row.getObject("id_legal_process", Long.class),
            row.getString("case_code"),
            row.getString("responsible_dpi"),
            row.getString("status"),
            row.getLong("version"),
            row.getString("sync_state"),
            row.getObject("request_id", UUID.class),
            row.getString("request_hash"),
            row.getString("created_by"),
            recurrence,
            null,
            row.getString("google_event_id")
        );
    };

    public Optional<Event> find(long id) {
        return jdbc
            .query(SELECT + " where a.id=?", MAPPER, id)
            .stream()
            .findFirst();
    }

    /** Busca la solicitud original para resolver reintentos sin crear otra actividad. */
    public Optional<Event> findRequest(UUID id) {
        return jdbc
            .query(SELECT + " where a.request_id=?", MAPPER, id)
            .stream()
            .findFirst();
    }

    public record ExceptionRow(
        long eventId,
        Instant original,
        String title,
        String description,
        Instant startsAt,
        Instant endsAt,
        String location,
        String status,
        long revision,
        String googleId,
        String etag,
        long syncedRevision
    ) {}

    private static final RowMapper<ExceptionRow> EXCEPTION_MAPPER = (row, rowIndex) ->
        new ExceptionRow(
            row.getLong("event_id"),
            row.getTimestamp("original_starts_at").toInstant(),
            row.getString("title"),
            row.getString("description"),
            row.getTimestamp("starts_at").toInstant(),
            row.getTimestamp("ends_at").toInstant(),
            row.getString("location"),
            row.getString("status"),
            row.getLong("revision"),
            row.getString("google_event_id"),
            row.getString("etag"),
            row.getLong("synced_revision")
        );

    public List<ExceptionRow> exceptions(long id) {
        return jdbc.query("select * from agenda_event_exception where event_id=?", EXCEPTION_MAPPER, id);
    }

    /** Aplica los cambios de una ocurrencia; el estado final de la serie prevalece sobre sus excepciones. */
    private Event override(Event master, ExceptionRow row) {
        String status = row.status();
        if (!master.status().equals("SCHEDULED")) {
            status = master.status();
        }
        return AgendaRecurrence.copy(
            master,
            row.title(),
            row.description(),
            row.startsAt(),
            row.endsAt(),
            row.location(),
            status,
            row.original(),
            row.googleId()
        );
    }

    public Event occurrence(Event master, Instant original) {
        if (
            master.recurrence() == null ||
            !AgendaRecurrence.starts(master.startsAt(), master.timeZone(), master.recurrence()).contains(
                original
            )
        ) {
            throw new OperationException(HttpStatus.BAD_REQUEST, "La ocurrencia no pertenece a esta serie.");
        }
        for (ExceptionRow row : exceptions(master.id())) {
            if (row.original().equals(original)) {
                return override(master, row);
            }
        }
        return AgendaRecurrence.occurrence(master, original);
    }

    public List<Event> expanded(Instant from, Instant to, Long caseId, String clientDpi, String status) {
        List<Object> params = new ArrayList<>(List.of(Timestamp.from(to), Timestamp.from(from)));
        StringBuilder where = new StringBuilder(
            " where ((a.starts_at<? and a.ends_at>?) or a.recurrence_frequency is not null)"
        );
        if (caseId != null) {
            where.append(" and a.id_legal_process=?");
            params.add(caseId);
        }
        if (clientDpi != null) {
            where.append(" and a.client_dpi=?");
            params.add(clientDpi);
        }
        List<Event> masters = jdbc.query(
            SELECT + where + " order by a.id limit 5001",
            MAPPER,
            params.toArray()
        );
        if (masters.size() > 5000) {
            throw new OperationException(
                HttpStatus.BAD_REQUEST,
                "Reduce la consulta mediante un cliente o expediente."
            );
        }
        Map<Long, Map<Instant, ExceptionRow>> overrides = new HashMap<>();
        if (!masters.isEmpty()) {
            String placeholders = String.join(",", Collections.nCopies(masters.size(), "?"));
            for (ExceptionRow row : jdbc.query(
                "select * from agenda_event_exception where event_id in (" + placeholders + ")",
                EXCEPTION_MAPPER,
                masters.stream().map(Event::id).toArray()
            )) {
                overrides.computeIfAbsent(row.eventId(), key -> new HashMap<>()).put(row.original(), row);
            }
        }
        List<Event> result = new ArrayList<>();
        for (Event master : masters) {
            Map<Instant, ExceptionRow> changes = overrides.getOrDefault(master.id(), Map.of());
            for (Instant start : AgendaRecurrence.starts(
                master.startsAt(),
                master.timeZone(),
                master.recurrence()
            )) {
                Event event = AgendaRecurrence.occurrence(master, start);
                if (changes.containsKey(start)) {
                    event = override(master, changes.get(start));
                }
                if (
                    event.startsAt().isBefore(to) &&
                    event.endsAt().isAfter(from) &&
                    (status == null || status.equals(event.status()))
                ) {
                    result.add(event);
                }
                if (result.size() > 10000) {
                    throw new OperationException(
                        HttpStatus.BAD_REQUEST,
                        "Hay demasiadas ocurrencias. Reduce el período consultado."
                    );
                }
            }
        }
        result.sort(Comparator.comparing(Event::startsAt).thenComparingLong(Event::id));
        return result;
    }

    /** Comprueba coincidencias de horario, incluyendo las ocurrencias y excepciones de otras series. */
    public boolean overlap(EventRequest request, String responsible, long excludedId) {
        return overlap(request, responsible, excludedId, null);
    }

    /** Comprueba coincidencias de horario, incluyendo las ocurrencias y excepciones de otras series. */
    public boolean overlap(
        EventRequest request,
        String responsible,
        long excludedId,
        Instant excludedOccurrence
    ) {
        if (request.type() == ActivityType.PAYMENT_REMINDER) {
            return false;
        }
        var starts = AgendaRecurrence.starts(request.startsAt(), request.timeZone(), request.recurrence());
        Instant to = AgendaRecurrence.end(
            starts.get(starts.size() - 1),
            request.startsAt(),
            request.endsAt(),
            request.timeZone(),
            request.allDay()
        );
        for (Event other : expanded(request.startsAt(), to, null, null, "SCHEDULED")) {
            if (!responsible.equals(other.responsibleDpi()) || other.type().equals("PAYMENT_REMINDER")) {
                continue;
            }
            if (
                other.id() == excludedId &&
                (excludedOccurrence == null || excludedOccurrence.equals(other.originalStartsAt()))
            ) {
                continue;
            }
            for (Instant start : starts) {
                Instant end = AgendaRecurrence.end(
                    start,
                    request.startsAt(),
                    request.endsAt(),
                    request.timeZone(),
                    request.allDay()
                );
                if (start.isBefore(other.endsAt()) && end.isAfter(other.startsAt())) {
                    return true;
                }
            }
        }
        return false;
    }

    public PageResponse<Event> search(
        Instant from,
        Instant to,
        Long caseId,
        String clientDpi,
        String status,
        int page,
        int size
    ) {
        List<Event> all = expanded(from, to, caseId, clientDpi, status);
        long offset = Math.multiplyExact((long) page, size);
        int begin = all.size();
        if (offset < all.size()) {
            begin = (int) offset;
        }
        return new PageResponse<>(
            all.subList(begin, Math.min(all.size(), begin + size)),
            page,
            size,
            all.size(),
            (int) Math.ceil((double) all.size() / size)
        );
    }

    private String frequency(Recurrence recurrence) {
        if (recurrence == null) {
            return null;
        }
        return recurrence.frequency().name();
    }

    private LocalDate until(Recurrence recurrence) {
        if (recurrence == null) {
            return null;
        }
        return recurrence.until();
    }

    public long insert(EventRequest request, String client, String responsible, String actor, String hash) {
        return Objects.requireNonNull(
            jdbc.queryForObject(
                """
                insert into legal_process_calendar(id_legal_process,event_title,event_description,event_date,client_dpi,responsible_dpi,
                    starts_at,ends_at,time_zone,all_day,activity_type,location,created_by,request_id,request_hash,recurrence_frequency,recurrence_until)
                values(?,?,?,(?::timestamptz at time zone ?),?,?,?,?,?,?,?,?,?,?,?,?,?) returning id
                """,
                Long.class,
                request.caseId(),
                request.title().strip(),
                request.description(),
                Timestamp.from(request.startsAt()),
                request.timeZone(),
                client,
                responsible,
                Timestamp.from(request.startsAt()),
                Timestamp.from(request.endsAt()),
                request.timeZone(),
                request.allDay(),
                request.type().name(),
                request.location(),
                actor,
                request.requestId(),
                hash,
                frequency(request.recurrence()),
                until(request.recurrence())
            )
        );
    }

    public void update(long id, EventRequest request, String client) {
        jdbc.update(
            """
            update legal_process_calendar set id_legal_process=?,client_dpi=?,event_title=?,event_description=?,event_date=(?::timestamptz at time zone ?),
              starts_at=?,ends_at=?,time_zone=?,all_day=?,activity_type=?,location=?,recurrence_frequency=?,recurrence_until=?,
              version=version+1,master_revision=master_revision+1,updated_at=now() where id=?
            """,
            request.caseId(),
            client,
            request.title().strip(),
            request.description(),
            Timestamp.from(request.startsAt()),
            request.timeZone(),
            Timestamp.from(request.startsAt()),
            Timestamp.from(request.endsAt()),
            request.timeZone(),
            request.allDay(),
            request.type().name(),
            request.location(),
            frequency(request.recurrence()),
            until(request.recurrence()),
            id
        );
    }

    public void status(long id, EventStatus status) {
        jdbc.update(
            "update legal_process_calendar set status=?,is_completed=?,version=version+1,master_revision=master_revision+1,updated_at=now() where id=?",
            status.name(),
            status == EventStatus.COMPLETED,
            id
        );
    }

    /** Persiste únicamente la fecha modificada y avanza la versión de la serie para detectar cambios concurrentes. */
    public void saveException(Event master, Instant original, EventRequest request, String status) {
        jdbc.update(
            "update legal_process_calendar set version=version+1,updated_at=now() where id=?",
            master.id()
        );
        jdbc.update(
            """
            insert into agenda_event_exception(event_id,original_starts_at,starts_at,ends_at,title,description,location,status,revision)
            values(?,?,?,?,?,?,?,?,?) on conflict(event_id,original_starts_at) do update set starts_at=excluded.starts_at,ends_at=excluded.ends_at,
                title=excluded.title,description=excluded.description,location=excluded.location,status=excluded.status,revision=excluded.revision
            """,
            master.id(),
            Timestamp.from(original),
            Timestamp.from(request.startsAt()),
            Timestamp.from(request.endsAt()),
            request.title(),
            request.description(),
            request.location(),
            status,
            master.version() + 1
        );
    }

    /** Registra la nueva versión y su trabajo de publicación dentro de la transacción de la actividad. */
    public void changed(Event event, String action, String actor, String reason) {
        jdbc.update(
            """
            insert into agenda_event_history(event_id,action,operator_dpi,event_version,reason,event_title,starts_at,ends_at,event_status,original_starts_at)
            values(?,?,?,?,?,?,?,?,?,?)
            """,
            event.id(),
            action,
            actor,
            event.version(),
            reason,
            event.title(),
            Timestamp.from(event.startsAt()),
            Timestamp.from(event.endsAt()),
            event.status(),
            timestamp(event.originalStartsAt())
        );
        jdbc.update(
            "update agenda_sync_outbox set completed_at=now(),last_error='SUPERSEDED' where event_id=? and completed_at is null and event_version<?",
            event.id(),
            event.version()
        );
        jdbc.update(
            """
            insert into agenda_sync_outbox(event_id,event_version,connection_id)
            values(?,?,(select id from google_calendar_connection where owner_dpi=? and state='CONNECTED')) on conflict do nothing
            """,
            event.id(),
            event.version(),
            actor
        );
        jdbc.update("update agenda_google_event set state='PENDING' where event_id=?", event.id());
    }

    public List<DayCount> days(Instant from, Instant to, Long caseId, String clientDpi, String status) {
        ZoneId zone = ZoneId.of("America/Guatemala");
        Map<LocalDate, Long> counts = new TreeMap<>();
        for (Event event : expanded(from, to, caseId, clientDpi, status)) {
            Instant start = event.startsAt();
            if (start.isBefore(from)) {
                start = from;
            }
            Instant end = event.endsAt();
            if (end.isAfter(to)) {
                end = to;
            }
            LocalDate last = end.minusNanos(1).atZone(zone).toLocalDate();
            for (
                LocalDate day = start.atZone(zone).toLocalDate();
                !day.isAfter(last);
                day = day.plusDays(1)
            ) {
                counts.merge(day, 1L, Long::sum);
            }
        }
        return counts
            .entrySet()
            .stream()
            .map(entry -> new DayCount(entry.getKey(), entry.getValue()))
            .toList();
    }

    public List<History> history(long id) {
        return jdbc.query(
            """
            select h.*,u.first_name || ' ' || u.last_name as operator_name from agenda_event_history h
            join user_system u on u.dpi=h.operator_dpi where event_id=? order by event_version desc
            """,
            (row, rowIndex) ->
                new History(
                    row.getString("action"),
                    row.getString("operator_name"),
                    row.getLong("event_version"),
                    row.getString("reason"),
                    row.getTimestamp("recorded_at").toInstant(),
                    row.getString("event_title"),
                    row.getTimestamp("starts_at").toInstant(),
                    row.getTimestamp("ends_at").toInstant(),
                    row.getString("event_status"),
                    instant(row.getTimestamp("original_starts_at"))
                ),
            id
        );
    }

    private Timestamp timestamp(Instant value) {
        if (value == null) {
            return null;
        }
        return Timestamp.from(value);
    }

    private Instant instant(Timestamp value) {
        if (value == null) {
            return null;
        }
        return value.toInstant();
    }
}
