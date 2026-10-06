package com.seminario.legaladministrator.modules.agenda;

import static com.seminario.legaladministrator.modules.agenda.AgendaDtos.*;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.*;

/** Encapsula las llamadas HTTPS a Google, la publicación limitada de datos y la traducción de errores del proveedor. */
@Component
public class GoogleCalendarGateway {

    public static final class Failure extends RuntimeException {

        private final String code;

        public Failure(String code) {
            super(code);
            this.code = code;
        }

        public String code() {
            return code;
        }

        public boolean retryable() {
            return code.equals("TEMPORARY");
        }
    }

    private final RestClient http;
    private final GoogleCalendarProperties properties;

    @Autowired
    public GoogleCalendarGateway(GoogleCalendarProperties properties) {
        this.properties = properties;
        var client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
        var factory = new JdkClientHttpRequestFactory(client);
        factory.setReadTimeout(Duration.ofSeconds(10));
        this.http = RestClient.builder().requestFactory(factory).build();
    }

    // Constructor para pruebas sin conexiones reales a Google.
    GoogleCalendarGateway(GoogleCalendarProperties properties, RestClient http) {
        this.properties = properties;
        this.http = http;
    }

    /** Canjea el código OAuth en el backend; el secreto del cliente nunca se envía al navegador. */
    public Map<String, Object> exchange(String code) {
        return token(
            Map.of(
                "code",
                code,
                "redirect_uri",
                properties.getFrontendOrigin(),
                "grant_type",
                "authorization_code"
            )
        );
    }

    /** Obtiene un token de acceso usando la credencial de renovación descifrada solo durante la operación. */
    public String refresh(String refreshToken) {
        Map<String, Object> result = token(
            Map.of("refresh_token", refreshToken, "grant_type", "refresh_token")
        );
        return required(result, "access_token");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> token(Map<String, String> values) {
        var form = new LinkedMultiValueMap<String, String>();
        values.forEach(form::add);
        form.add("client_id", properties.getClientId());
        form.add("client_secret", properties.getClientSecret());
        try {
            return http
                .post()
                .uri("https://oauth2.googleapis.com/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(Map.class);
        } catch (RestClientResponseException e) {
            throw failure(e, true);
        } catch (RestClientException e) {
            throw new Failure("TEMPORARY");
        }
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> identity(String token) {
        try {
            return http
                .get()
                .uri("https://openidconnect.googleapis.com/v1/userinfo")
                .headers(h -> h.setBearerAuth(token))
                .retrieve()
                .body(Map.class);
        } catch (RestClientResponseException e) {
            throw failure(e, false);
        } catch (RestClientException e) {
            throw new Failure("TEMPORARY");
        }
    }

    /** Comprueba que la cuenta conserve permiso de edición sobre el calendario configurado. */
    @SuppressWarnings("unchecked")
    public void validateCalendar(String token) {
        try {
            Map<String, Object> result = http
                .get()
                .uri(builder ->
                    builder
                        .scheme("https")
                        .host("www.googleapis.com")
                        .pathSegment("calendar", "v3", "calendars", properties.getCalendarId(), "events")
                        .queryParam("maxResults", 1)
                        .build()
                )
                .headers(h -> h.setBearerAuth(token))
                .retrieve()
                .body(Map.class);
            String role = required(result, "accessRole");
            if (!role.equals("writer") && !role.equals("owner")) {
                throw new Failure("NO_PERMISSION");
            }
        } catch (RestClientResponseException e) {
            throw failure(e, false);
        } catch (RestClientException e) {
            throw new Failure("TEMPORARY");
        }
    }

    public record Synced(String etag) {}

    /** Publica o actualiza la actividad interna y usa el ETag para detectar cambios externos concurrentes. */
    @SuppressWarnings("unchecked")
    public Synced publish(String token, String calendar, String eventId, String etag, Event event) {
        String uri = calendarUri(calendar) + "/" + encode(eventId);
        if (event.status().equals("CANCELLED")) {
            if (etag == null) {
                try {
                    Map<String, Object> existing = http
                        .get()
                        .uri(uri)
                        .headers(h -> h.setBearerAuth(token))
                        .retrieve()
                        .body(Map.class);
                    if (existing == null || !ownedBy(existing, event.id())) {
                        throw new Failure("CONFLICT");
                    }
                    etag = required(existing, "etag");
                } catch (RestClientResponseException e) {
                    if (e.getStatusCode().value() == 404 || e.getStatusCode().value() == 410) {
                        return new Synced(null);
                    }
                    throw failure(e, false);
                } catch (RestClientException e) {
                    throw new Failure("TEMPORARY");
                }
            }
            Map<String, Object> remote;
            try {
                remote = getEvent(token, calendar, eventId);
            } catch (Failure failure) {
                if (failure.code().equals("MISSING_EVENT")) {
                    return new Synced(null);
                }
                throw failure;
            }
            if (hasGuests(remote)) {
                throw new Failure("UNSUPPORTED_GUESTS");
            }
            String cancelEtag = etag;
            try {
                http.delete()
                    .uri(uri)
                    .headers(h -> {
                        h.setBearerAuth(token);
                        h.setIfMatch(cancelEtag);
                    })
                    .retrieve()
                    .toBodilessEntity();
            } catch (RestClientResponseException e) {
                if (e.getStatusCode().value() != 404 && e.getStatusCode().value() != 410) {
                    throw failure(e, false);
                }
            } catch (RestClientException e) {
                throw new Failure("TEMPORARY");
            }
            return new Synced(null);
        }
        String previousEtag = etag;
        Map<String, Object> body = payload(event);
        try {
            Map<String, Object> result;
            if (etag == null) {
                body.put("id", eventId);
                result = http
                    .post()
                    .uri(calendarUri(calendar))
                    .headers(h -> h.setBearerAuth(token))
                    .body(body)
                    .retrieve()
                    .body(Map.class);
            } else {
                Map<String, Object> current = getEvent(token, calendar, eventId);
                if (hasGuests(current)) {
                    throw new Failure("UNSUPPORTED_GUESTS");
                }
                if (!previousEtag.equals(required(current, "etag"))) {
                    if (matches(current, event)) {
                        return new Synced(required(current, "etag"));
                    }
                    throw new Failure("CONFLICT");
                }
                body.putAll(
                    GoogleEventMapper.times(
                        new GoogleEventMapper.Schedule(
                            event.startsAt(),
                            event.endsAt(),
                            event.timeZone(),
                            event.allDay()
                        )
                    )
                );
                body.remove("reminders");
                preserveProperties(current, body);
                result = patchEvent(token, calendar, eventId, previousEtag, body);
            }
            return new Synced(required(result, "etag"));
        } catch (RestClientResponseException e) {
            if (etag == null && e.getStatusCode().value() == 409) {
                // La respuesta de creación pudo perderse. Verificar antes de adoptar el evento.
                Map<String, Object> existing;
                try {
                    existing = http
                        .get()
                        .uri(uri)
                        .headers(h -> h.setBearerAuth(token))
                        .retrieve()
                        .body(Map.class);
                } catch (RestClientResponseException lookup) {
                    throw failure(lookup, false);
                } catch (RestClientException lookup) {
                    throw new Failure("TEMPORARY");
                }
                if (existing == null || !ownedBy(existing, event.id())) {
                    throw new Failure("CONFLICT");
                }
                if (hasGuests(existing)) {
                    throw new Failure("UNSUPPORTED_GUESTS");
                }
                if (!matches(existing, event)) {
                    throw new Failure("CONFLICT");
                }
                return new Synced(required(existing, "etag"));
            }
            throw failure(e, false);
        } catch (RestClientException e) {
            throw new Failure("TEMPORARY");
        }
    }

    private boolean matches(Map<String, Object> remote, Event event) {
        try {
            var schedule = GoogleEventMapper.schedule(remote, event.timeZone());
            if (
                !schedule.start().equals(event.startsAt()) ||
                !schedule.end().equals(event.endsAt()) ||
                !Objects.equals(remote.get("summary"), payload(event).get("summary"))
            ) {
                return false;
            }
            if (
                event.originalStartsAt() == null &&
                !GoogleEventMapper.rules(remote).equals(
                    AgendaRecurrence.rules(event.recurrence(), event.timeZone(), event.allDay())
                )
            ) {
                return false;
            }
            return ownedBy(remote, event.id());
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    private boolean ownedBy(Map<String, Object> remote, long eventId) {
        if (!(remote.get("extendedProperties") instanceof Map<?, ?> properties)) {
            return false;
        }
        if (!(properties.get("private") instanceof Map<?, ?> values)) {
            return false;
        }
        return Long.toString(eventId).equals(values.get("legalAgendaId"));
    }

    private void preserveProperties(Map<String, Object> remote, Map<String, Object> body) {
        Map<String, Object> extended = new HashMap<>();
        Map<String, Object> privateValues = new HashMap<>();
        if (remote.get("extendedProperties") instanceof Map<?, ?> properties) {
            properties.forEach((key, value) -> {
                if (key instanceof String text) {
                    extended.put(text, value);
                }
            });
            if (properties.get("private") instanceof Map<?, ?> values) {
                values.forEach((key, value) -> {
                    if (key instanceof String text) {
                        privateValues.put(text, value);
                    }
                });
            }
        }
        if (
            body.get("extendedProperties") instanceof Map<?, ?> properties &&
            properties.get("private") instanceof Map<?, ?> values
        ) {
            values.forEach((key, value) -> {
                if (key instanceof String text) {
                    privateValues.put(text, value);
                }
            });
        }
        extended.put("private", privateValues);
        body.put("extendedProperties", extended);
    }

    /** Construye la representación pública de una actividad interna sin copiar notas privadas ni datos personales. */
    public Map<String, Object> payload(Event event) {
        Map<String, Object> body = new HashMap<>();
        // Ni nombres de clientes, DPI, lugares, notas privadas ni archivos salen de la aplicación.
        String summary = "Actividad del despacho";
        if (event.caseCode() != null) {
            summary += " — " + event.caseCode();
        }
        if (event.status().equals("COMPLETED")) {
            summary = "Realizada: " + summary;
        }
        body.put("summary", summary);
        body.put("visibility", "private");
        if (event.allDay()) {
            var zone = java.time.ZoneId.of(event.timeZone());
            body.put("start", Map.of("date", event.startsAt().atZone(zone).toLocalDate().toString()));
            body.put("end", Map.of("date", event.endsAt().atZone(zone).toLocalDate().toString()));
        } else {
            body.put("start", Map.of("dateTime", event.startsAt().toString(), "timeZone", event.timeZone()));
            body.put("end", Map.of("dateTime", event.endsAt().toString(), "timeZone", event.timeZone()));
        }
        body.put("extendedProperties", Map.of("private", Map.of("legalAgendaId", Long.toString(event.id()))));
        body.put("reminders", Map.of("useDefault", true));
        if (event.originalStartsAt() == null) {
            body.put(
                "recurrence",
                AgendaRecurrence.rules(event.recurrence(), event.timeZone(), event.allDay())
            );
        }
        return body;
    }

    @SuppressWarnings("unchecked")
    public Optional<String> currentEtag(String token, String calendar, String externalId, long eventId) {
        try {
            Map<String, Object> result = http
                .get()
                .uri(calendarUri(calendar) + "/" + encode(externalId))
                .headers(h -> h.setBearerAuth(token))
                .retrieve()
                .body(Map.class);
            if (result == null || !ownedBy(result, eventId)) {
                throw new Failure("CONFLICT");
            }
            if ("cancelled".equals(result.get("status"))) {
                return Optional.empty();
            }
            return Optional.of(required(result, "etag"));
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 404 || e.getStatusCode().value() == 410) {
                return Optional.empty();
            }
            throw failure(e, false);
        } catch (RestClientException e) {
            throw new Failure("TEMPORARY");
        }
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> listEvents(
        String token,
        String calendar,
        Instant from,
        Instant to,
        String pageToken
    ) {
        try {
            return http
                .get()
                .uri(builder -> {
                    builder
                        .scheme("https")
                        .host("www.googleapis.com")
                        .pathSegment("calendar", "v3", "calendars", calendar, "events")
                        .queryParam("timeMin", from.toString())
                        .queryParam("timeMax", to.toString())
                        .queryParam("singleEvents", true)
                        .queryParam("orderBy", "startTime")
                        .queryParam("maxResults", 250);
                    if (pageToken != null) {
                        builder.queryParam("pageToken", pageToken);
                    }
                    return builder.build();
                })
                .headers(headers -> headers.setBearerAuth(token))
                .retrieve()
                .body(Map.class);
        } catch (RestClientResponseException exception) {
            throw failure(exception, false);
        } catch (RestClientException exception) {
            throw new Failure("TEMPORARY");
        }
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> getEvent(String token, String calendar, String eventId) {
        try {
            return http
                .get()
                .uri(calendarUri(calendar) + "/" + encode(eventId))
                .headers(headers -> headers.setBearerAuth(token))
                .retrieve()
                .body(Map.class);
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().value() == 404 || exception.getStatusCode().value() == 410) {
                throw new Failure("MISSING_EVENT");
            }
            throw failure(exception, false);
        } catch (RestClientException exception) {
            throw new Failure("TEMPORARY");
        }
    }

    /** Aplica únicamente los campos editados y exige If-Match para evitar sobrescrituras silenciosas. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> patchEvent(
        String token,
        String calendar,
        String eventId,
        String etag,
        Map<String, Object> body
    ) {
        try {
            return http
                .patch()
                .uri(calendarUri(calendar) + "/" + encode(eventId))
                .headers(headers -> {
                    headers.setBearerAuth(token);
                    headers.setIfMatch(etag);
                })
                .body(body)
                .retrieve()
                .body(Map.class);
        } catch (RestClientResponseException exception) {
            throw failure(exception, false);
        } catch (RestClientException exception) {
            throw new Failure("TEMPORARY");
        }
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> instance(
        String token,
        String calendar,
        String seriesId,
        Instant original,
        String timeZone
    ) {
        try {
            Map<String, Object> result = http
                .get()
                .uri(builder ->
                    builder
                        .scheme("https")
                        .host("www.googleapis.com")
                        .pathSegment("calendar", "v3", "calendars", calendar, "events", seriesId, "instances")
                        .queryParam("originalStart", original.toString())
                        .queryParam("showDeleted", true)
                        .queryParam("maxResults", 10)
                        .build()
                )
                .headers(headers -> headers.setBearerAuth(token))
                .retrieve()
                .body(Map.class);
            if (result != null && result.get("items") instanceof List<?> items) {
                for (Object item : items) {
                    if (item instanceof Map<?, ?> raw) {
                        Map<String, Object> event = (Map<String, Object>) raw;
                        Object start = event.get("originalStartTime");
                        if (start instanceof Map<?, ?> time) {
                            if (original.toString().equals(time.get("dateTime"))) {
                                return event;
                            }
                            if (
                                time.get("dateTime") instanceof String value &&
                                Instant.parse(value).equals(original)
                            ) {
                                return event;
                            }
                            if (
                                time.get("date") instanceof String date &&
                                date.equals(
                                    original.atZone(java.time.ZoneId.of(timeZone)).toLocalDate().toString()
                                )
                            ) {
                                return event;
                            }
                        }
                    }
                }
            }
            throw new Failure("CONFLICT");
        } catch (RestClientResponseException exception) {
            throw failure(exception, false);
        } catch (RestClientException exception) {
            throw new Failure("TEMPORARY");
        }
    }

    public static boolean hasGuests(Map<String, Object> event) {
        return event != null && event.get("attendees") instanceof List<?> guests && !guests.isEmpty();
    }

    public void revoke(String token) {
        var form = new LinkedMultiValueMap<String, String>();
        form.add("token", token);
        try {
            http.post()
                .uri("https://oauth2.googleapis.com/revoke")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .toBodilessEntity();
        } catch (RestClientException e) {
            throw new Failure("TEMPORARY");
        }
    }

    private String calendarUri(String id) {
        return "https://www.googleapis.com/calendar/v3/calendars/" + encode(id) + "/events";
    }

    private String encode(String text) {
        return org.springframework.web.util.UriUtils.encodePathSegment(text, StandardCharsets.UTF_8);
    }

    public static String required(Map<String, Object> map, String key) {
        if (map == null || !(map.get(key) instanceof String value) || value.isBlank()) {
            throw new Failure("INVALID_RESPONSE");
        }
        return value;
    }

    /** Clasifica cuota y fallos transitorios por separado de credenciales revocadas y permisos insuficientes. */
    private Failure failure(RestClientResponseException e, boolean tokenRequest) {
        int status = e.getStatusCode().value();
        String body = e.getResponseBodyAsString();
        boolean quota =
            body.contains("rateLimitExceeded") ||
            body.contains("userRateLimitExceeded") ||
            body.contains("quotaExceeded");
        if (status == 429 || status >= 500 || (status == 403 && quota)) {
            return new Failure("TEMPORARY");
        }
        if (status == 401 || (tokenRequest && status == 400)) {
            return new Failure("REAUTH_REQUIRED");
        }
        if (status == 403 || status == 404) {
            return new Failure("NO_PERMISSION");
        }
        if (status == 409 || status == 412) {
            return new Failure("CONFLICT");
        }
        return new Failure("INVALID_EVENT");
    }
}
