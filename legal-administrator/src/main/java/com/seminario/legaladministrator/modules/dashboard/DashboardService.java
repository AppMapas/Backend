package com.seminario.legaladministrator.modules.dashboard;

import static com.seminario.legaladministrator.modules.dashboard.DashboardDtos.*;

import com.seminario.legaladministrator.config.security.OfficeAccess;
import com.seminario.legaladministrator.modules.agenda.AgendaDtos.Event;
import com.seminario.legaladministrator.modules.agenda.AgendaService;
import com.seminario.legaladministrator.shared.OperationException;
import com.seminario.legaladministrator.shared.PageResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Lectura coherente de la agenda local y los expedientes; Google se consulta por separado. */
@Service
@PreAuthorize("hasAnyAuthority('Abogada','Administrador') and @officeAccess.allowed(authentication)")
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, timeout = 15)
public class DashboardService {

    private final DashboardRepository repository;
    private final AgendaService agenda;
    private final OfficeAccess access;
    private final Clock clock;

    public DashboardService(
        DashboardRepository repository,
        AgendaService agenda,
        OfficeAccess access,
        @Qualifier("dashboardClock") Clock clock
    ) {
        this.repository = repository;
        this.agenda = agenda;
        this.access = access;
        this.clock = clock;
    }

    public Summary summary() {
        access.current();
        Instant generatedAt = clock.instant();
        LocalDate today = generatedAt.atZone(DashboardRules.ZONE).toLocalDate();
        List<Event> scheduled = scheduled(today);
        List<Activity> todayEvents = scheduled
            .stream()
            .filter(event -> DashboardRules.overlaps(event.startsAt(), event.endsAt(), today))
            .map(this::activity)
            .toList();
        List<Reminder> reminders = reminders(scheduled, today);
        List<DayCount> week = new ArrayList<>();
        for (int offset = 0; offset < DashboardRules.WEEK_DAYS; offset++) {
            LocalDate date = today.plusDays(offset);
            long count = scheduled
                .stream()
                .filter(event -> DashboardRules.overlaps(event.startsAt(), event.endsAt(), date))
                .count();
            week.add(new DayCount(date, count));
        }
        return new Summary(
            generatedAt,
            today,
            DashboardRules.ZONE.getId(),
            repository.activeCases(),
            todayEvents.size(),
            todayEvents.stream().limit(DashboardRules.AGENDA_PREVIEW).toList(),
            new ReminderCounts(
                count(reminders, ReminderKind.TODAY),
                count(reminders, ReminderKind.UPCOMING),
                count(reminders, ReminderKind.UNATTENDED)
            ),
            reminders.stream().limit(DashboardRules.REMINDER_PREVIEW).toList(),
            today.minusDays(DashboardRules.UNATTENDED_DAYS),
            today.plusDays(6),
            List.copyOf(week)
        );
    }

    public RemindersPage remindersPage(ReminderKind kind, int page, int size) {
        access.current();
        if (page < 0 || page > 10000 || size < 1 || size > 25) {
            throw new OperationException(
                HttpStatus.BAD_REQUEST,
                "Consulta páginas válidas de hasta 25 recordatorios."
            );
        }
        Instant generatedAt = clock.instant();
        LocalDate today = generatedAt.atZone(DashboardRules.ZONE).toLocalDate();
        List<Reminder> reminders = reminders(scheduled(today), today)
            .stream()
            .filter(reminder -> kind == null || reminder.kind() == kind)
            .toList();
        int begin = Math.min(reminders.size(), page * size);
        int end = Math.min(reminders.size(), begin + size);
        return new RemindersPage(
            generatedAt,
            today,
            today.minusDays(DashboardRules.UNATTENDED_DAYS),
            today.plusDays(6),
            new PageResponse<>(
                reminders.subList(begin, end),
                page,
                size,
                reminders.size(),
                (int) Math.ceil((double) reminders.size() / size)
            )
        );
    }

    private List<Event> scheduled(LocalDate today) {
        // Reutiliza la expansión de series y excepciones de Agenda, con una ventana finita de 37 días.
        return agenda
            .calendar(
                DashboardRules.start(today.minusDays(DashboardRules.UNATTENDED_DAYS)),
                DashboardRules.start(today.plusDays(DashboardRules.WEEK_DAYS)),
                null,
                null,
                "SCHEDULED"
            )
            .stream()
            .filter(event -> "SCHEDULED".equals(event.status()))
            .sorted(Comparator.comparing(Event::startsAt).thenComparingLong(Event::id))
            .toList();
    }

    private List<Reminder> reminders(List<Event> scheduled, LocalDate today) {
        List<Reminder> result = new ArrayList<>();
        for (Event event : scheduled) {
            if (!"PAYMENT_REMINDER".equals(event.type())) {
                continue;
            }
            ReminderKind kind;
            if (DashboardRules.overlaps(event.startsAt(), event.endsAt(), today)) {
                kind = ReminderKind.TODAY;
            } else if (!event.endsAt().isAfter(DashboardRules.start(today))) {
                if (
                    event
                        .startsAt()
                        .isBefore(DashboardRules.start(today.minusDays(DashboardRules.UNATTENDED_DAYS)))
                ) {
                    continue;
                }
                kind = ReminderKind.UNATTENDED;
            } else {
                kind = ReminderKind.UPCOMING;
            }
            result.add(new Reminder(kind, activity(event)));
        }
        // Lo pendiente de atender aparece primero; luego los recordatorios de hoy y los próximos.
        result.sort(
            Comparator.comparingInt((Reminder reminder) -> priority(reminder.kind()))
                .thenComparing(reminder -> reminder.activity().startsAt())
                .thenComparingLong(reminder -> reminder.activity().id())
        );
        return List.copyOf(result);
    }

    private int priority(ReminderKind kind) {
        if (kind == ReminderKind.UNATTENDED) {
            return 0;
        }
        if (kind == ReminderKind.TODAY) {
            return 1;
        }
        return 2;
    }

    private long count(List<Reminder> reminders, ReminderKind kind) {
        return reminders
            .stream()
            .filter(reminder -> reminder.kind() == kind)
            .count();
    }

    private Activity activity(Event event) {
        return new Activity(
            event.id(),
            event.title(),
            event.type(),
            event.startsAt(),
            event.endsAt(),
            event.timeZone(),
            event.allDay(),
            event.caseId(),
            event.caseCode(),
            event.originalStartsAt()
        );
    }
}
