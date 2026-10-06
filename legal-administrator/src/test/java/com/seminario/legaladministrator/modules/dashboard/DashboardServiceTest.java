package com.seminario.legaladministrator.modules.dashboard;

import static com.seminario.legaladministrator.modules.dashboard.DashboardDtos.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.seminario.legaladministrator.config.security.OfficeAccess;
import com.seminario.legaladministrator.modules.agenda.AgendaDtos.Event;
import com.seminario.legaladministrator.modules.agenda.AgendaService;
import java.time.*;
import java.util.*;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DashboardServiceTest {

    private final DashboardRepository repository = mock(DashboardRepository.class);
    private final AgendaService agenda = mock(AgendaService.class);
    private final OfficeAccess access = mock(OfficeAccess.class);
    private final LocalDate today = LocalDate.of(2027, 1, 1);
    private DashboardService service;

    @BeforeEach
    void prepare() {
        service = new DashboardService(
            repository,
            agenda,
            access,
            Clock.fixed(Instant.parse("2027-01-01T16:00:00Z"), ZoneOffset.UTC)
        );
        when(repository.activeCases()).thenReturn(103L);
        rows(List.of());
    }

    private void rows(List<Event> events) {
        when(agenda.calendar(any(), any(), isNull(), isNull(), eq("SCHEDULED"))).thenReturn(events);
    }

    private Event event(long id, String type, Instant start, Instant end, String status) {
        return new Event(
            id,
            "Actividad " + id,
            "Nota privada",
            type,
            start,
            end,
            "America/Guatemala",
            false,
            null,
            "1000000000001",
            "Cliente",
            9L,
            "EXP-9",
            "3002234560901",
            status,
            0L,
            "LOCAL",
            UUID.randomUUID(),
            "hash",
            "3002234560901",
            null,
            start,
            null
        );
    }

    @Test
    void countsWholePopulationAndLimitsOnlyThePreview() {
        rows(
            IntStream.range(0, 20)
                .mapToObj(index ->
                    event(
                        index + 1,
                        "FOLLOW_UP",
                        DashboardRules.start(today).plusSeconds(index * 60),
                        DashboardRules.start(today).plusSeconds(index * 60 + 30),
                        "SCHEDULED"
                    )
                )
                .toList()
        );
        Summary summary = service.summary();
        assertThat(summary.activeCases()).isEqualTo(103);
        assertThat(summary.todayActivities()).isEqualTo(20);
        assertThat(summary.agenda()).hasSize(8);
        assertThat(summary.week().get(0).count()).isEqualTo(20);
        assertThat(summary.week()).hasSize(7);
        verify(access).current();
    }

    @Test
    void usesOfficeDateBeforeUtcMidnightAndCrossesYearCorrectly() {
        service = new DashboardService(
            repository,
            agenda,
            access,
            Clock.fixed(Instant.parse("2027-01-01T05:59:59Z"), ZoneOffset.UTC)
        );
        Summary summary = service.summary();
        assertThat(summary.date()).isEqualTo(LocalDate.of(2026, 12, 31));
        assertThat(summary.week().get(1).date()).isEqualTo(today);
        verify(agenda).calendar(
            Instant.parse("2026-12-01T06:00:00Z"),
            Instant.parse("2027-01-07T06:00:00Z"),
            null,
            null,
            "SCHEDULED"
        );
    }

    @Test
    void includesOngoingActivitiesAndRespectsExclusiveEnd() {
        rows(
            List.of(
                event(
                    1,
                    "FOLLOW_UP",
                    DashboardRules.start(today.minusDays(1)),
                    DashboardRules.start(today),
                    "SCHEDULED"
                ),
                event(
                    2,
                    "FOLLOW_UP",
                    DashboardRules.start(today.minusDays(1)),
                    DashboardRules.start(today).plusSeconds(60),
                    "SCHEDULED"
                )
            )
        );
        assertThat(service.summary().agenda()).extracting(Activity::id).containsExactly(2L);
    }

    @Test
    void classifiesRemindersWithoutInferringDebtAndOmitsFinishedOccurrences() {
        rows(
            List.of(
                event(
                    1,
                    "PAYMENT_REMINDER",
                    DashboardRules.start(today).plusSeconds(3600),
                    DashboardRules.start(today).plusSeconds(7200),
                    "SCHEDULED"
                ),
                event(
                    2,
                    "PAYMENT_REMINDER",
                    DashboardRules.start(today.minusDays(1)),
                    DashboardRules.start(today),
                    "SCHEDULED"
                ),
                event(
                    3,
                    "PAYMENT_REMINDER",
                    DashboardRules.start(today.plusDays(1)),
                    DashboardRules.start(today.plusDays(1)).plusSeconds(60),
                    "SCHEDULED"
                ),
                event(
                    4,
                    "PAYMENT_REMINDER",
                    DashboardRules.start(today),
                    DashboardRules.start(today).plusSeconds(60),
                    "CANCELLED"
                ),
                event(
                    5,
                    "PAYMENT_REMINDER",
                    DashboardRules.start(today),
                    DashboardRules.start(today).plusSeconds(60),
                    "COMPLETED"
                )
            )
        );
        Summary summary = service.summary();
        assertThat(summary.reminders()).isEqualTo(new ReminderCounts(1, 1, 1));
        assertThat(summary.reminderPreview())
            .extracting(Reminder::kind)
            .containsExactly(ReminderKind.UNATTENDED, ReminderKind.TODAY, ReminderKind.UPCOMING);
    }

    @Test
    void reminderThatSpansMidnightIsTodayAndNotUnattended() {
        rows(
            List.of(
                event(
                    1,
                    "PAYMENT_REMINDER",
                    DashboardRules.start(today.minusDays(1)),
                    DashboardRules.start(today).plusSeconds(60),
                    "SCHEDULED"
                )
            )
        );
        assertThat(service.summary().reminders()).isEqualTo(new ReminderCounts(1, 0, 0));
    }

    @Test
    void unattendedLookbackDoesNotIncludeOlderLongEvents() {
        rows(
            List.of(
                event(
                    1,
                    "PAYMENT_REMINDER",
                    DashboardRules.start(today.minusDays(31)),
                    DashboardRules.start(today.minusDays(29)),
                    "SCHEDULED"
                )
            )
        );
        assertThat(service.summary().reminders().unattended()).isZero();
    }

    @Test
    void returnsZeroOnlyWhenTheSuccessfulQueriesAreEmpty() {
        when(repository.activeCases()).thenReturn(0L);
        Summary summary = service.summary();
        assertThat(summary.agenda()).isEmpty();
        assertThat(summary.week()).allMatch(day -> day.count() == 0);
        assertThat(summary.reminders()).isEqualTo(new ReminderCounts(0, 0, 0));
    }

    @Test
    void propagatesRepositoryFailureInsteadOfInventingStatistics() {
        when(repository.activeCases()).thenThrow(new IllegalStateException("consulta fallida"));
        assertThatThrownBy(service::summary).hasMessage("consulta fallida");
    }

    @Test
    void filtersAndPaginatesRemindersWithoutChangingTheTotal() {
        rows(
            IntStream.range(0, 15)
                .mapToObj(index ->
                    event(
                        index + 1,
                        "PAYMENT_REMINDER",
                        DashboardRules.start(today).plusSeconds(index * 60),
                        DashboardRules.start(today).plusSeconds(index * 60 + 30),
                        "SCHEDULED"
                    )
                )
                .toList()
        );
        var result = service.remindersPage(ReminderKind.TODAY, 1, 10).reminders();
        assertThat(result.totalElements()).isEqualTo(15);
        assertThat(result.totalPages()).isEqualTo(2);
        assertThat(result.content()).hasSize(5);
        assertThat(
            service.remindersPage(ReminderKind.UNATTENDED, 0, 10).reminders().totalElements()
        ).isZero();
        assertThat(service.remindersPage(null, 10000, 25).reminders().content()).isEmpty();
    }

    @Test
    void rejectsUnboundedPagination() {
        assertThatThrownBy(() -> service.remindersPage(null, -1, 10)).hasMessageContaining("páginas");
        assertThatThrownBy(() -> service.remindersPage(null, 0, 26)).hasMessageContaining("páginas");
    }
}
