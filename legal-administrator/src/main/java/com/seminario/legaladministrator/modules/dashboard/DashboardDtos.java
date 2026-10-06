package com.seminario.legaladministrator.modules.dashboard;

import com.seminario.legaladministrator.shared.PageResponse;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Contratos del resumen operativo. Esta entrega no consulta ni devuelve importes monetarios. */
public final class DashboardDtos {

    private DashboardDtos() {}

    public enum ReminderKind {
        TODAY,
        UPCOMING,
        UNATTENDED,
    }

    /** Identidad y horario suficientes para abrir Agenda, sin notas privadas ni DPI. */
    public record Activity(
        long id,
        String title,
        String type,
        Instant startsAt,
        Instant endsAt,
        String timeZone,
        boolean allDay,
        Long caseId,
        String caseCode,
        Instant originalStartsAt
    ) {}

    public record Reminder(ReminderKind kind, Activity activity) {}

    public record ReminderCounts(long today, long upcoming, long unattended) {}

    public record DayCount(LocalDate date, long count) {}

    public record Summary(
        Instant generatedAt,
        LocalDate date,
        String timeZone,
        long activeCases,
        long todayActivities,
        List<Activity> agenda,
        ReminderCounts reminders,
        List<Reminder> reminderPreview,
        LocalDate unattendedFrom,
        LocalDate upcomingThrough,
        List<DayCount> week
    ) {}

    public record RemindersPage(
        Instant generatedAt,
        LocalDate date,
        LocalDate unattendedFrom,
        LocalDate upcomingThrough,
        PageResponse<Reminder> reminders
    ) {}
}
