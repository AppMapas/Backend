package com.seminario.legaladministrator.modules.agenda;

import static com.seminario.legaladministrator.modules.agenda.AgendaDtos.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.seminario.legaladministrator.config.security.OfficeAccess;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class GoogleAgendaServiceTest {

    GoogleConnectionService connections = mock(GoogleConnectionService.class);
    GoogleCalendarGateway google = mock(GoogleCalendarGateway.class);
    GoogleTokenCipher cipher = mock(GoogleTokenCipher.class);
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    OfficeAccess office = mock(OfficeAccess.class);
    GoogleAgendaService service = new GoogleAgendaService(
        connections,
        google,
        cipher,
        jdbc,
        mock(AgendaRepository.class),
        office
    );
    Instant start = Instant.parse("2027-01-01T15:00:00Z");

    Map<String, Object> event() {
        return new HashMap<>(
            Map.of(
                "id",
                "legal123",
                "etag",
                "tag",
                "summary",
                "Evento externo",
                "status",
                "confirmed",
                "start",
                Map.of("dateTime", start.toString(), "timeZone", "America/Guatemala"),
                "end",
                Map.of("dateTime", start.plusSeconds(3600).toString())
            )
        );
    }

    void connection() {
        var actor = new com.seminario.legaladministrator.modules.users.UserSystemEntity();
        actor.setDpi("actor");
        when(office.current()).thenReturn(actor);
        when(connections.connected(false)).thenReturn(
            new GoogleConnectionService.Connection(
                2,
                "actor",
                "subject",
                "mail",
                "office",
                "encrypted",
                "v1",
                "CONNECTED",
                0
            )
        );
        when(cipher.decrypt("encrypted", "actor", "subject", "v1")).thenReturn("refresh");
        when(google.refresh("refresh")).thenReturn("access");
    }

    @Test
    void invalidIdentifiersAndWindowsNeverReachGoogle() {
        assertThatThrownBy(() -> service.get("../../other-calendar")).hasMessageContaining("identificador");
        assertThatThrownBy(() ->
            service.list(start, start.plusSeconds(100L * 86400), null)
        ).hasMessageContaining("93");
        assertThatThrownBy(() ->
            service.list(start, start.plusSeconds(86400), "\ninvalid")
        ).hasMessageContaining("página");
        verifyNoInteractions(google, connections);
    }

    @Test
    void externalEventDoesNotTrustForgedLocalMetadataOrUnsafeLinks() {
        connection();
        var event = event();
        event.put("htmlLink", "javascript:alert(1)");
        event.put("extendedProperties", Map.of("private", Map.of("legalAgendaId", "1")));
        when(google.getEvent("access", "office", "legal123")).thenReturn(event);
        var result = service.get("legal123");
        assertThat(result.localEventId()).isNull();
        assertThat(result.calendarUrl()).startsWith("https://calendar.google.com/");
    }

    @Test
    void existingGuestsAreReadOnlyAndAreNeverPatched() {
        connection();
        var event = event();
        event.put("attendees", List.of(Map.of("email", "private@example.test")));
        when(google.getEvent("access", "office", "legal123")).thenReturn(event);
        assertThat(service.get("legal123").editable()).isFalse();
        var request = new GoogleEditRequest(
            "Editado",
            null,
            start,
            start.plusSeconds(3600),
            "America/Guatemala",
            false,
            "tag",
            "ONE",
            null
        );
        assertThatThrownBy(() -> service.update("legal123", request)).hasMessageContaining("Google Calendar");
        verify(google, never()).patchEvent(anyString(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void staleEtagCannotOverwriteTheRemoteEvent() {
        connection();
        when(google.getEvent("access", "office", "legal123")).thenReturn(event());
        var request = new GoogleEditRequest(
            "Editado",
            null,
            start,
            start.plusSeconds(3600),
            "America/Guatemala",
            false,
            "stale",
            "ONE",
            null
        );
        assertThatThrownBy(() -> service.update("legal123", request)).hasMessageContaining("Recarga");
        verify(google, never()).patchEvent(anyString(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void sanitizedListCarriesTheNextPageAndOmitsProviderSecrets() {
        connection();
        var event = event();
        event.put("private-secret", "value");
        when(google.listEvents("access", "office", start, start.plusSeconds(86400), null)).thenReturn(
            Map.of("items", List.of(event), "nextPageToken", "page2")
        );
        var result = service.list(start, start.plusSeconds(86400), null);
        assertThat(result.items()).hasSize(1);
        assertThat(result.nextPageToken()).isEqualTo("page2");
        assertThat(result.toString()).doesNotContain("private-secret");
    }

    @Test
    void editingOnlyPatchesManagedFieldsAndAuditsTheAuthenticatedOperator() {
        connection();
        when(google.getEvent("access", "office", "legal123")).thenReturn(event());
        when(google.patchEvent(eq("access"), eq("office"), eq("legal123"), eq("tag"), any())).thenReturn(
            event()
        );
        service.update(
            "legal123",
            new GoogleEditRequest(
                "Editado",
                "Público",
                start,
                start.plusSeconds(3600),
                "America/Guatemala",
                false,
                "tag",
                "ONE",
                null
            )
        );
        verify(google).patchEvent(
            eq("access"),
            eq("office"),
            eq("legal123"),
            eq("tag"),
            argThat(
                body ->
                    body.containsKey("summary") &&
                    !body.containsKey("attendees") &&
                    !body.containsKey("attachments") &&
                    !body.containsKey("reminders") &&
                    !body.containsKey("recurrence")
            )
        );
        verify(jdbc).update(contains("agenda_google_audit"), eq("actor"), eq("office"), eq("legal123"));
    }

    @Test
    void googleQueriesAreBoundedPerAuthenticatedUser() {
        connection();
        when(google.getEvent("access", "office", "legal123")).thenReturn(event());
        for (int call = 0; call < 60; call++) service.get("legal123");
        assertThatThrownBy(() -> service.get("legal123"))
            .isInstanceOf(com.seminario.legaladministrator.shared.OperationException.class)
            .hasMessageContaining("límite");
        verify(google, times(60)).getEvent("access", "office", "legal123");
    }
}
