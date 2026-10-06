package com.seminario.legaladministrator.modules.agenda;

import static com.seminario.legaladministrator.modules.agenda.AgendaDtos.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.seminario.legaladministrator.config.security.OfficeAccess;
import com.seminario.legaladministrator.modules.processes.repository.LegalProcessRepository;
import com.seminario.legaladministrator.modules.processes.service.CaseRequestGuard;
import com.seminario.legaladministrator.modules.users.*;
import com.seminario.legaladministrator.modules.users.repository.ClientUserRepository;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AgendaServiceTest {

    AgendaRepository repo = mock(AgendaRepository.class);
    OfficeAccess access = mock(OfficeAccess.class);
    LegalProcessRepository cases = mock(LegalProcessRepository.class);
    ClientUserRepository clients = mock(ClientUserRepository.class);
    CaseRequestGuard guard = mock(CaseRequestGuard.class);
    AgendaService service = new AgendaService(repo, access, cases, clients, guard);

    @BeforeEach
    void prepare() {
        var actor = new UserSystemEntity();
        actor.setDpi("3002234560901");
        when(access.current()).thenReturn(actor);
        var client = new ClientUserEntity();
        client.setDpi("1000000000001");
        client.setActive(true);
        when(clients.findById(client.getDpi())).thenReturn(Optional.of(client));
        when(guard.fingerprint(anyList())).thenReturn("hash");
    }

    EventRequest request(UUID id) {
        Instant start = Instant.now().plusSeconds(86400);
        return new EventRequest(
            "Consulta",
            null,
            ActivityType.APPOINTMENT,
            start,
            start.plusSeconds(3600),
            "America/Guatemala",
            false,
            null,
            "1000000000001",
            null,
            id,
            0L
        );
    }

    Event event(UUID requestId, String creator, long version) {
        return new Event(
            1,
            "Consulta",
            null,
            "APPOINTMENT",
            Instant.now().minusSeconds(3600),
            Instant.now(),
            "America/Guatemala",
            false,
            null,
            "1000000000001",
            "Ana",
            null,
            null,
            "3002234560901",
            "SCHEDULED",
            version,
            "LOCAL",
            requestId,
            "hash",
            creator
        );
    }

    @Test
    void retriesReuseTheOriginalEventWithoutSchedulingTwice() {
        UUID id = UUID.randomUUID();
        when(repo.findRequest(id)).thenReturn(Optional.of(event(id, "3002234560901", 0)));
        assertThat(service.create(request(id)).replayed()).isTrue();
        verify(repo, never()).insert(any(), any(), any(), any(), any());
    }

    @Test
    void anotherUserCannotReuseARequestIdentifier() {
        UUID id = UUID.randomUUID();
        when(repo.findRequest(id)).thenReturn(Optional.of(event(id, "other", 0)));
        assertThatThrownBy(() -> service.create(request(id))).hasMessageContaining("otros datos");
    }

    @Test
    void availabilityIsCheckedAfterTheWriteLock() {
        var r = request(UUID.randomUUID());
        when(repo.findRequest(r.requestId())).thenReturn(Optional.empty());
        when(repo.overlap(r, "3002234560901", -1)).thenReturn(true);
        assertThatThrownBy(() -> service.create(r)).hasMessageContaining("horario");
        var order = inOrder(repo);
        order.verify(repo).lockWrites();
        order.verify(repo).findRequest(r.requestId());
        order.verify(repo).overlap(r, "3002234560901", -1);
        verify(repo, never()).insert(any(), any(), any(), any(), any());
    }

    @Test
    void cancellationNeedsReasonAndStaleVersionsCannotChangeEvents() {
        when(repo.find(1)).thenReturn(Optional.of(event(UUID.randomUUID(), "3002234560901", 2)));
        assertThatThrownBy(() ->
            service.changeStatus(1, new StatusRequest(EventStatus.CANCELLED, 1L, "Motivo"))
        ).hasMessageContaining("cambió");
        assertThatThrownBy(() ->
            service.changeStatus(1, new StatusRequest(EventStatus.CANCELLED, 2L, " "))
        ).hasMessageContaining("motivo");
        verify(repo, never()).status(anyLong(), any());
    }

    @Test
    void completingAnActivityOnlyChangesAgendaAndQueuesSynchronization() {
        Event e = event(UUID.randomUUID(), "3002234560901", 0);
        when(repo.find(1)).thenReturn(Optional.of(e));
        service.changeStatus(1, new StatusRequest(EventStatus.COMPLETED, 0L, null));
        verify(repo).status(1, EventStatus.COMPLETED);
        verify(repo).changed(e, "COMPLETED", "3002234560901", null);
        verifyNoInteractions(cases, clients);
    }
}
