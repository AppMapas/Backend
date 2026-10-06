package com.seminario.legaladministrator.modules.agenda;

import static com.seminario.legaladministrator.modules.agenda.AgendaDtos.*;
import static org.assertj.core.api.Assertions.*;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AgendaRulesTest {

    static EventRequest request(
        Instant start,
        Instant end,
        boolean allDay,
        String zone,
        ActivityType type,
        Long caseId
    ) {
        return new EventRequest(
            "Consulta",
            null,
            type,
            start,
            end,
            zone,
            allDay,
            null,
            "1000000000001",
            caseId,
            UUID.randomUUID(),
            0L
        );
    }

    @Test
    void validatesIntervalsAndAllDayInGuatemala() {
        Instant start = Instant.parse("2027-01-01T06:00:00Z");
        assertThatCode(() ->
            AgendaRules.validate(
                request(
                    start,
                    start.plusSeconds(86400),
                    true,
                    "America/Guatemala",
                    ActivityType.APPOINTMENT,
                    null
                )
            )
        ).doesNotThrowAnyException();
        assertThatThrownBy(() ->
            AgendaRules.validate(
                request(start, start, true, "America/Guatemala", ActivityType.APPOINTMENT, null)
            )
        ).hasMessageContaining("posterior");
        assertThatThrownBy(() ->
            AgendaRules.validate(
                request(
                    start,
                    start.plusSeconds(3600),
                    true,
                    "America/Guatemala",
                    ActivityType.APPOINTMENT,
                    null
                )
            )
        ).hasMessageContaining("medianoche");
        assertThatThrownBy(() ->
            AgendaRules.validate(
                request(
                    start,
                    start.plusSeconds(32L * 86400),
                    false,
                    "America/Guatemala",
                    ActivityType.APPOINTMENT,
                    null
                )
            )
        ).hasMessageContaining("31 días");
    }

    @Test
    void invalidTimezoneAndMissingCaseAreRejected() {
        Instant start = Instant.now();
        assertThatThrownBy(() ->
            AgendaRules.validate(
                request(start, start.plusSeconds(60), false, "Inventada", ActivityType.HEARING, 1L)
            )
        ).hasMessageContaining("zona");
        assertThatThrownBy(() ->
            AgendaRules.validate(
                request(start, start.plusSeconds(60), false, "UTC", ActivityType.PAYMENT_REMINDER, null)
            )
        ).hasMessageContaining("expediente");
    }

    @Test
    void finalStatesCannotBeChangedAndRangesAreBounded() {
        assertThatCode(() ->
            AgendaRules.transition(EventStatus.SCHEDULED, EventStatus.CANCELLED)
        ).doesNotThrowAnyException();
        assertThatThrownBy(() ->
            AgendaRules.transition(EventStatus.COMPLETED, EventStatus.SCHEDULED)
        ).hasMessageContaining("programada");
        assertThatThrownBy(() ->
            AgendaRules.transition(EventStatus.CANCELLED, EventStatus.COMPLETED)
        ).hasMessageContaining("programada");
        Instant start = Instant.now();
        assertThatThrownBy(() ->
            AgendaRules.window(start, start.plusSeconds(94L * 86400))
        ).hasMessageContaining("93 días");
    }

    @Test
    void reconciliationRequiresAnExplicitScheduleChoice() {
        try (var factory = jakarta.validation.Validation.buildDefaultValidatorFactory()) {
            var violations = factory.getValidator().validate(new AgendaDtos.ReconcileRequest(0L, null, null));
            assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("useAppSchedule"));
        }
    }
}
