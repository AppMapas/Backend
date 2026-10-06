package com.seminario.legaladministrator.modules.agenda;

import jakarta.validation.constraints.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Define los contratos HTTP de Agenda. Los campos de versión y ocurrencia permiten detectar ediciones concurrentes. */
public final class AgendaDtos {

    private AgendaDtos() {}

    public enum ActivityType {
        APPOINTMENT,
        HEARING,
        PRESENTATION,
        DELIVERY,
        FOLLOW_UP,
        PAYMENT_REMINDER,
    }

    public enum EventStatus {
        SCHEDULED,
        COMPLETED,
        CANCELLED,
    }

    public enum Frequency {
        DAILY,
        WEEKLY,
        MONTHLY,
        YEARLY,
    }

    public record Recurrence(@NotNull Frequency frequency, @NotNull LocalDate until) {}

    public record EventRequest(
        @NotBlank @Size(max = 150) String title,
        @Size(max = 2000) String description,
        @NotNull ActivityType type,
        @NotNull Instant startsAt,
        @NotNull Instant endsAt,
        @NotBlank @Size(max = 60) String timeZone,
        boolean allDay,
        @Size(max = 255) String location,
        @Pattern(regexp = "[0-9]{13}") String clientDpi,
        @Positive Long caseId,
        UUID requestId,
        @PositiveOrZero Long version,
        @jakarta.validation.Valid Recurrence recurrence
    ) {
        public EventRequest(
            String title,
            String description,
            ActivityType type,
            Instant startsAt,
            Instant endsAt,
            String timeZone,
            boolean allDay,
            String location,
            String clientDpi,
            Long caseId,
            UUID requestId,
            Long version
        ) {
            this(
                title,
                description,
                type,
                startsAt,
                endsAt,
                timeZone,
                allDay,
                location,
                clientDpi,
                caseId,
                requestId,
                version,
                null
            );
        }
    }

    public record StatusRequest(
        @NotNull EventStatus status,
        @NotNull @PositiveOrZero Long version,
        @Size(max = 500) String reason
    ) {}

    public record Event(
        long id,
        String title,
        String description,
        String type,
        Instant startsAt,
        Instant endsAt,
        String timeZone,
        boolean allDay,
        String location,
        String clientDpi,
        String clientName,
        Long caseId,
        String caseCode,
        String responsibleDpi,
        String status,
        long version,
        String syncState,
        UUID requestId,
        @com.fasterxml.jackson.annotation.JsonIgnore String requestHash,
        @com.fasterxml.jackson.annotation.JsonIgnore String createdBy,
        Recurrence recurrence,
        Instant originalStartsAt,
        String googleEventId
    ) {
        public Event(
            long id,
            String title,
            String description,
            String type,
            Instant startsAt,
            Instant endsAt,
            String timeZone,
            boolean allDay,
            String location,
            String clientDpi,
            String clientName,
            Long caseId,
            String caseCode,
            String responsibleDpi,
            String status,
            long version,
            String syncState,
            UUID requestId,
            String requestHash,
            String createdBy
        ) {
            this(
                id,
                title,
                description,
                type,
                startsAt,
                endsAt,
                timeZone,
                allDay,
                location,
                clientDpi,
                clientName,
                caseId,
                caseCode,
                responsibleDpi,
                status,
                version,
                syncState,
                requestId,
                requestHash,
                createdBy,
                null,
                null,
                null
            );
        }
    }

    public record DayCount(java.time.LocalDate date, long count) {}

    public record History(
        String action,
        String operatorName,
        long version,
        String reason,
        Instant recordedAt,
        String title,
        Instant startsAt,
        Instant endsAt,
        String status,
        Instant originalStartsAt
    ) {}

    public record Creation(Event event, boolean replayed) {}

    public record GoogleStatus(
        boolean enabled,
        String clientId,
        String scope,
        String state,
        String accountEmail,
        boolean canManage,
        long pendingCount
    ) {}

    public record OAuthIntent(String state) {}

    public record ReconcileRequest(
        @NotNull @PositiveOrZero Long version,
        @NotNull Boolean useAppSchedule,
        Instant originalStartsAt
    ) {
        public ReconcileRequest(Long version, boolean useAppSchedule) {
            this(version, useAppSchedule, null);
        }
    }

    public record OccurrenceRequest(
        @NotNull Instant originalStartsAt,
        @NotNull @jakarta.validation.Valid EventRequest event
    ) {}

    public record OccurrenceStatusRequest(
        @NotNull Instant originalStartsAt,
        @NotNull @jakarta.validation.Valid StatusRequest change
    ) {}

    public record GoogleEvent(
        String id,
        String title,
        String description,
        Instant startsAt,
        Instant endsAt,
        String timeZone,
        boolean allDay,
        String status,
        String etag,
        String seriesId,
        Instant originalStartsAt,
        List<String> recurrence,
        boolean editable,
        boolean hasGuests,
        Long localEventId,
        boolean linkedConflict,
        String calendarUrl,
        Long caseId,
        String clientDpi
    ) {}

    public record GooglePage(List<GoogleEvent> items, String nextPageToken, Instant checkedAt) {}

    public record GoogleEditRequest(
        @NotBlank @Size(max = 150) String title,
        @Size(max = 2000) String description,
        @NotNull Instant startsAt,
        @NotNull Instant endsAt,
        @NotBlank @Size(max = 60) String timeZone,
        boolean allDay,
        @NotBlank @Size(max = 255) String etag,
        @NotBlank @Pattern(regexp = "ONE|ALL") @Size(max = 10) String scope,
        @jakarta.validation.Valid Recurrence recurrence,
        boolean clearRecurrence
    ) {
        public GoogleEditRequest(
            String title,
            String description,
            Instant startsAt,
            Instant endsAt,
            String timeZone,
            boolean allDay,
            String etag,
            String scope,
            Recurrence recurrence
        ) {
            this(title, description, startsAt, endsAt, timeZone, allDay, etag, scope, recurrence, false);
        }
    }

    public record ConnectRequest(
        @NotBlank @Size(max = 4096) String code,
        @NotBlank @Size(max = 128) String state
    ) {}
}
