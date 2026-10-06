package com.seminario.legaladministrator.modules.agenda;

import static com.seminario.legaladministrator.modules.agenda.AgendaDtos.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.http.*;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class GoogleCalendarGatewayTest {

    @Test
    void springCreatesGatewayWithoutGoogleCredentials() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.register(GoogleCalendarProperties.class, GoogleCalendarGateway.class);
            context.refresh();

            assertThat(context.getBean(GoogleCalendarProperties.class).configured()).isFalse();
            assertThat(context.getBean(GoogleCalendarGateway.class)).isNotNull();
        }
    }

    Event event(String status) {
        return new Event(
            1,
            "Nombre privado",
            "DPI privado",
            "APPOINTMENT",
            Instant.parse("2027-01-01T06:00:00Z"),
            Instant.parse("2027-01-02T06:00:00Z"),
            "America/Guatemala",
            true,
            "Dirección privada",
            "1000000000001",
            "Nombre de cliente",
            1L,
            "EXP-1",
            "actor",
            status,
            0,
            "LOCAL",
            UUID.randomUUID(),
            "hash",
            "actor"
        );
    }

    @Test
    void onlyExportsMinimalReferencesAndExclusiveAllDayEnd() {
        var gateway = new GoogleCalendarGateway(new GoogleCalendarProperties());
        var payload = gateway.payload(event("SCHEDULED"));
        assertThat(payload.toString()).doesNotContain(
            "DPI privado",
            "Nombre privado",
            "1000000000001",
            "Nombre de cliente",
            "Dirección privada"
        );
        assertThat(payload).doesNotContainKeys("attendees", "attachments", "description", "location");
        assertThat(payload.get("start")).isEqualTo(java.util.Map.of("date", "2027-01-01"));
        assertThat(payload.get("end")).isEqualTo(java.util.Map.of("date", "2027-01-02"));
    }

    @Test
    void providerUnauthorizedIsSanitizedAndTokenRevocationMeansReconnect() {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        var gateway = new GoogleCalendarGateway(new GoogleCalendarProperties(), builder.build());
        server
            .expect(requestTo("https://oauth2.googleapis.com/token"))
            .andRespond(
                withStatus(HttpStatus.BAD_REQUEST)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{\"error\":\"invalid_grant\",\"secret\":\"private-value\"}")
            );
        assertThatThrownBy(() -> gateway.refresh("refresh-secret"))
            .isInstanceOf(GoogleCalendarGateway.Failure.class)
            .hasMessage("REAUTH_REQUIRED")
            .hasMessageNotContaining("private-value");
        server.verify();
    }

    @Test
    void cancelRecoversLostCreationResponseAndDeletesTheExistingEvent() {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        var gateway = new GoogleCalendarGateway(new GoogleCalendarProperties(), builder.build());
        String uri = "https://www.googleapis.com/calendar/v3/calendars/office/events/legal1";
        server
            .expect(requestTo(uri))
            .andExpect(method(HttpMethod.GET))
            .andRespond(
                withSuccess(
                    "{\"etag\":\"tag\",\"extendedProperties\":{\"private\":{\"legalAgendaId\":\"1\"}}}",
                    MediaType.APPLICATION_JSON
                )
            );
        server
            .expect(requestTo(uri))
            .andExpect(method(HttpMethod.GET))
            .andRespond(
                withSuccess(
                    "{\"etag\":\"tag\",\"extendedProperties\":{\"private\":{\"legalAgendaId\":\"1\"}}}",
                    MediaType.APPLICATION_JSON
                )
            );
        server
            .expect(requestTo(uri))
            .andExpect(method(HttpMethod.DELETE))
            .andExpect(header("If-Match", "tag"))
            .andRespond(withNoContent());
        assertThat(gateway.publish("access", "office", "legal1", null, event("CANCELLED")).etag()).isNull();
        server.verify();
    }

    @Test
    void externalChangesCannotBeOverwritten() {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        var gateway = new GoogleCalendarGateway(new GoogleCalendarProperties(), builder.build());
        server
            .expect(requestTo("https://www.googleapis.com/calendar/v3/calendars/office/events/legal1"))
            .andExpect(method(HttpMethod.GET))
            .andRespond(withSuccess("{\"etag\":\"old-tag\"}", MediaType.APPLICATION_JSON));
        server
            .expect(requestTo("https://www.googleapis.com/calendar/v3/calendars/office/events/legal1"))
            .andExpect(method(HttpMethod.PATCH))
            .andExpect(header("If-Match", "old-tag"))
            .andRespond(withStatus(HttpStatus.PRECONDITION_FAILED));
        assertThatThrownBy(() ->
            gateway.publish("access", "office", "legal1", "old-tag", event("SCHEDULED"))
        ).hasMessage("CONFLICT");
        server.verify();
    }

    @Test
    void aLostPatchResponseIsRecoveredWithoutOverwritingANewerChange() {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        var gateway = new GoogleCalendarGateway(new GoogleCalendarProperties(), builder.build());
        var remote = new java.util.HashMap<>(gateway.payload(event("SCHEDULED")));
        remote.put("etag", "new-tag");
        String body = new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(remote).toString();
        server
            .expect(requestTo("https://www.googleapis.com/calendar/v3/calendars/office/events/legal1"))
            .andExpect(method(HttpMethod.GET))
            .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
        assertThat(
            gateway.publish("access", "office", "legal1", "old-tag", event("SCHEDULED")).etag()
        ).isEqualTo("new-tag");
        server.verify();
    }

    @Test
    void aDuplicateCreationCannotSilentlyOverwriteAnExternallyMovedEvent() {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        var gateway = new GoogleCalendarGateway(new GoogleCalendarProperties(), builder.build());
        server
            .expect(requestTo("https://www.googleapis.com/calendar/v3/calendars/office/events"))
            .andExpect(method(HttpMethod.POST))
            .andRespond(withStatus(HttpStatus.CONFLICT));
        server
            .expect(requestTo("https://www.googleapis.com/calendar/v3/calendars/office/events/legal1"))
            .andExpect(method(HttpMethod.GET))
            .andRespond(
                withSuccess(
                    "{\"etag\":\"changed\",\"extendedProperties\":{\"private\":{\"legalAgendaId\":\"1\"}},\"start\":{\"date\":\"2027-01-05\"},\"end\":{\"date\":\"2027-01-06\"}}",
                    MediaType.APPLICATION_JSON
                )
            );
        assertThatThrownBy(() ->
            gateway.publish("access", "office", "legal1", null, event("SCHEDULED"))
        ).hasMessage("CONFLICT");
        server.verify();
    }

    @Test
    void exceedingGoogleQuotaIsTemporaryAndDoesNotRequireReauthorization() {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        var gateway = new GoogleCalendarGateway(new GoogleCalendarProperties(), builder.build());
        server
            .expect(requestTo("https://www.googleapis.com/calendar/v3/calendars/office/events/legal1"))
            .andRespond(
                withStatus(HttpStatus.FORBIDDEN)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{\"error\":{\"errors\":[{\"reason\":\"userRateLimitExceeded\"}]}}")
            );
        assertThatThrownBy(() -> gateway.getEvent("access", "office", "legal1"))
            .isInstanceOf(GoogleCalendarGateway.Failure.class)
            .hasMessage("TEMPORARY");
        server.verify();
    }
}
