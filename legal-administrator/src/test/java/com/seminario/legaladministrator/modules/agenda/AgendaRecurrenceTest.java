package com.seminario.legaladministrator.modules.agenda;

import static com.seminario.legaladministrator.modules.agenda.AgendaDtos.*;
import static org.assertj.core.api.Assertions.*;

import java.time.*;
import org.junit.jupiter.api.Test;

class AgendaRecurrenceTest {

    @Test
    void monthlyRecurrenceSkipsInvalidDaysInsteadOfMovingThem() {
        var starts = AgendaRecurrence.starts(
            Instant.parse("2027-01-31T15:00:00Z"),
            "America/Guatemala",
            new Recurrence(Frequency.MONTHLY, LocalDate.parse("2027-04-30"))
        );
        assertThat(starts).containsExactly(
            Instant.parse("2027-01-31T15:00:00Z"),
            Instant.parse("2027-03-31T15:00:00Z")
        );
    }

    @Test
    void boundedSeriesRejectsUnlimitedOrInvalidRecurrence() {
        var start = Instant.parse("2027-01-01T15:00:00Z");
        assertThatThrownBy(() ->
            AgendaRecurrence.starts(
                start,
                "America/Guatemala",
                new Recurrence(Frequency.DAILY, LocalDate.parse("2029-01-01"))
            )
        ).hasMessageContaining("366");
        assertThatThrownBy(() ->
            AgendaRecurrence.starts(
                start,
                "America/Guatemala",
                new Recurrence(null, LocalDate.parse("2027-01-05"))
            )
        ).hasMessageContaining("frecuencia");
    }

    @Test
    void repeatsAtTheSameLocalHourAcrossDaylightSavingChanges() {
        var starts = AgendaRecurrence.starts(
            Instant.parse("2027-03-13T15:00:00Z"),
            "America/New_York",
            new Recurrence(Frequency.DAILY, LocalDate.parse("2027-03-15"))
        );
        assertThat(starts).containsExactly(
            Instant.parse("2027-03-13T15:00:00Z"),
            Instant.parse("2027-03-14T14:00:00Z"),
            Instant.parse("2027-03-15T14:00:00Z")
        );
    }

    @Test
    void exportsAnExclusiveEndAndUtcUntilWithExplicitZone() {
        assertThat(
            AgendaRecurrence.rules(
                new Recurrence(Frequency.WEEKLY, LocalDate.parse("2027-02-01")),
                "America/Guatemala"
            )
        ).containsExactly("RRULE:FREQ=WEEKLY;UNTIL=20270202T055959Z");
    }

    @Test
    void ignoresNonexistentHoursAndKeepsTimedDurationAcrossDst() {
        var starts = AgendaRecurrence.starts(
            Instant.parse("2027-03-13T07:30:00Z"),
            "America/New_York",
            new Recurrence(Frequency.DAILY, LocalDate.parse("2027-03-15"))
        );
        assertThat(starts).containsExactly(
            Instant.parse("2027-03-13T07:30:00Z"),
            Instant.parse("2027-03-15T06:30:00Z")
        );
        assertThat(
            AgendaRecurrence.end(
                Instant.parse("2027-03-14T06:30:00Z"),
                Instant.parse("2027-03-13T06:30:00Z"),
                Instant.parse("2027-03-13T08:30:00Z"),
                "America/New_York",
                false
            )
        ).isEqualTo(Instant.parse("2027-03-14T08:30:00Z"));
    }

    @Test
    void allDayRecurrenceUsesCalendarDatesAndPreservesMidnightAcrossDst() {
        assertThat(
            AgendaRecurrence.rules(
                new Recurrence(Frequency.DAILY, LocalDate.parse("2027-03-15")),
                "America/New_York",
                true
            )
        ).containsExactly("RRULE:FREQ=DAILY;UNTIL=20270315");
        assertThat(
            AgendaRecurrence.end(
                Instant.parse("2027-03-14T05:00:00Z"),
                Instant.parse("2027-03-13T05:00:00Z"),
                Instant.parse("2027-03-14T05:00:00Z"),
                "America/New_York",
                true
            )
        ).isEqualTo(Instant.parse("2027-03-15T04:00:00Z"));
    }
}
