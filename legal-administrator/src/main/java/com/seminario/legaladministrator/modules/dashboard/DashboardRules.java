package com.seminario.legaladministrator.modules.dashboard;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/** Los límites se calculan en la zona del despacho y el fin de cada día es exclusivo. */
public final class DashboardRules {

    public static final ZoneId ZONE = ZoneId.of("America/Guatemala");
    public static final int UNATTENDED_DAYS = 30;
    public static final int WEEK_DAYS = 7;
    public static final int AGENDA_PREVIEW = 8;
    public static final int REMINDER_PREVIEW = 5;

    private DashboardRules() {}

    public static Instant start(LocalDate date) {
        return date.atStartOfDay(ZONE).toInstant();
    }

    public static boolean overlaps(Instant startsAt, Instant endsAt, LocalDate date) {
        return startsAt.isBefore(start(date.plusDays(1))) && endsAt.isAfter(start(date));
    }
}
