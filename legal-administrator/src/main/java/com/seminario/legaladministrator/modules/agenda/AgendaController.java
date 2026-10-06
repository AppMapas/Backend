package com.seminario.legaladministrator.modules.agenda;

import static com.seminario.legaladministrator.modules.agenda.AgendaDtos.*;

import com.seminario.legaladministrator.shared.PageResponse;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** Expone la agenda interna y las operaciones de Google. Delega validaciones y permisos a los servicios. */
@RestController
@RequestMapping("/api/v1/agenda")
@RequiredArgsConstructor
@PreAuthorize("hasAnyAuthority('Abogada','Administrador') and @officeAccess.allowed(authentication)")
public class AgendaController {

    private final AgendaService agenda;
    private final GoogleConnectionService google;
    private final GoogleAgendaService external;

    @GetMapping("/events")
    public ResponseEntity<PageResponse<Event>> search(
        @RequestParam Instant from,
        @RequestParam Instant to,
        @RequestParam(required = false) Long caseId,
        @RequestParam(required = false) String clientDpi,
        @RequestParam(required = false) String status,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "25") int size
    ) {
        return ok(agenda.search(from, to, caseId, clientDpi, status, page, size));
    }

    @GetMapping("/calendar")
    public ResponseEntity<List<Event>> calendar(
        @RequestParam Instant from,
        @RequestParam Instant to,
        @RequestParam(required = false) Long caseId,
        @RequestParam(required = false) String clientDpi,
        @RequestParam(required = false) String status
    ) {
        return ok(agenda.calendar(from, to, caseId, clientDpi, status));
    }

    @GetMapping("/days")
    public ResponseEntity<List<DayCount>> days(
        @RequestParam Instant from,
        @RequestParam Instant to,
        @RequestParam(required = false) Long caseId,
        @RequestParam(required = false) String clientDpi,
        @RequestParam(required = false) String status
    ) {
        return ok(agenda.days(from, to, caseId, clientDpi, status));
    }

    @GetMapping("/upcoming")
    public ResponseEntity<PageResponse<Event>> upcoming(
        @RequestParam(required = false) Long caseId,
        @RequestParam(required = false) String clientDpi
    ) {
        Instant now = Instant.now();
        return ok(agenda.search(now, now.plusSeconds(30L * 86400), caseId, clientDpi, "SCHEDULED", 0, 10));
    }

    @GetMapping("/events/{id}")
    public ResponseEntity<Event> get(@PathVariable long id) {
        return ok(agenda.get(id));
    }

    @GetMapping("/events/{id}/occurrence")
    public ResponseEntity<Event> occurrence(@PathVariable long id, @RequestParam Instant originalStartsAt) {
        return ok(agenda.occurrence(id, originalStartsAt));
    }

    @PutMapping("/events/{id}/occurrence")
    public ResponseEntity<Event> updateOccurrence(
        @PathVariable long id,
        @Valid @RequestBody OccurrenceRequest request
    ) {
        return ok(agenda.updateOccurrence(id, request));
    }

    @PostMapping("/events/{id}/occurrence/status")
    public ResponseEntity<Event> occurrenceStatus(
        @PathVariable long id,
        @Valid @RequestBody OccurrenceStatusRequest request
    ) {
        return ok(agenda.changeOccurrenceStatus(id, request));
    }

    @GetMapping("/events/{id}/history")
    public ResponseEntity<List<History>> history(@PathVariable long id) {
        return ok(agenda.history(id));
    }

    @PostMapping("/events")
    public ResponseEntity<Event> create(@Valid @RequestBody EventRequest request) {
        Creation result = agenda.create(request);
        HttpStatus status = HttpStatus.CREATED;
        if (result.replayed()) {
            status = HttpStatus.OK;
        }
        return ResponseEntity.status(status)
            .cacheControl(CacheControl.noStore())
            .header("Idempotency-Replayed", Boolean.toString(result.replayed()))
            .body(result.event());
    }

    @PutMapping("/events/{id}")
    public ResponseEntity<Event> update(@PathVariable long id, @Valid @RequestBody EventRequest request) {
        return ok(agenda.update(id, request));
    }

    @PostMapping("/events/{id}/status")
    public ResponseEntity<Event> status(@PathVariable long id, @Valid @RequestBody StatusRequest request) {
        return ok(agenda.changeStatus(id, request));
    }

    @GetMapping("/google/events")
    public ResponseEntity<GooglePage> googleEvents(
        @RequestParam Instant from,
        @RequestParam Instant to,
        @RequestParam(required = false) String pageToken
    ) {
        return ok(external.list(from, to, pageToken));
    }

    @GetMapping("/google/events/{eventId}")
    public ResponseEntity<GoogleEvent> googleEvent(@PathVariable String eventId) {
        return ok(external.get(eventId));
    }

    @PutMapping("/google/events/{eventId}")
    public ResponseEntity<GoogleEvent> updateGoogleEvent(
        @PathVariable String eventId,
        @Valid @RequestBody GoogleEditRequest request
    ) {
        return ok(external.update(eventId, request));
    }

    @PostMapping("/events/{id}/google/assign")
    public ResponseEntity<Event> assign(@PathVariable long id, @Valid @RequestBody ReconcileRequest request) {
        return ok(google.assign(id, request.version()));
    }

    @GetMapping("/google/status")
    public ResponseEntity<GoogleStatus> connection() {
        return ok(google.status());
    }

    @PostMapping("/google/intent")
    public ResponseEntity<OAuthIntent> intent(
        @RequestHeader(value = "Origin", required = false) String origin,
        @RequestHeader(value = "X-Requested-With", required = false) String header
    ) {
        return ok(google.begin(origin, header));
    }

    @PostMapping("/google/connect")
    public ResponseEntity<GoogleStatus> connect(
        @Valid @RequestBody ConnectRequest request,
        @RequestHeader(value = "Origin", required = false) String origin,
        @RequestHeader(value = "X-Requested-With", required = false) String header
    ) {
        return ok(google.connect(request, origin, header));
    }

    @DeleteMapping("/google/connect")
    public ResponseEntity<GoogleStatus> disconnect() {
        return ok(google.disconnect());
    }

    @PostMapping("/events/{id}/google/reconcile")
    public ResponseEntity<Event> reconcile(
        @PathVariable long id,
        @Valid @RequestBody ReconcileRequest request
    ) {
        return ok(google.reconcile(id, request));
    }

    @PostMapping("/google/retry")
    public ResponseEntity<GoogleStatus> retry() {
        return ok(google.retry());
    }

    private <T> ResponseEntity<T> ok(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
