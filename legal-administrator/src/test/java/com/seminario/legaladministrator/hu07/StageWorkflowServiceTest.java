package com.seminario.legaladministrator.hu07;

import com.seminario.legaladministrator.config.security.OfficeAccess;
import com.seminario.legaladministrator.modules.processes.*;
import com.seminario.legaladministrator.modules.processes.dto.StageDtos.MoveRequest;
import com.seminario.legaladministrator.modules.processes.repository.*;
import com.seminario.legaladministrator.modules.processes.service.StageWorkflowService;
import com.seminario.legaladministrator.modules.users.UserSystemEntity;
import com.seminario.legaladministrator.shared.OperationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StageWorkflowServiceTest {
    @Mock LegalProcessRepository cases;
    @Mock ProcessTypeRepository templates;
    @Mock ProcessTypeStageRepository templateStages;
    @Mock ProcessTypeStageTransitionRepository templateEdges;
    @Mock LegalProcessStageRepository stages;
    @Mock LegalProcessStageTransitionRepository edges;
    @Mock LegalProcessStageEventRepository events;
    @Mock OfficeAccess access;
    StageWorkflowService service;

    @BeforeEach
    void setUp() {
        service = new StageWorkflowService(cases, templates, templateStages, templateEdges,
                stages, edges, events, access);
    }

    @Test
    void permittedTransitionUpdatesStageAndWritesOneAuditedEvent() {
        LegalProcessEntity legalProcess = legalProcess();
        LegalProcessStageEntity source = stage(10L, "PRESENTADO");
        LegalProcessStageEntity target = stage(11L, "REVISION");
        legalProcess.setCurrentStage(source);
        UserSystemEntity lawyer = actor();
        UUID requestId = UUID.randomUUID();
        when(access.current()).thenReturn(lawyer);
        when(cases.findForUpdate(7L)).thenReturn(Optional.of(legalProcess));
        when(stages.existsByLegalProcessId(7L)).thenReturn(true);
        when(stages.findByIdAndLegalProcessId(11L, 7L)).thenReturn(Optional.of(target));
        when(edges.existsByLegalProcessIdAndFromCodeAndToCode(7L, "PRESENTADO", "REVISION")).thenReturn(true);

        boolean replayed = service.move(7L, new MoveRequest(requestId, 2L, 11L, null, " Revisión iniciada "));

        assertThat(replayed).isFalse();
        assertThat(legalProcess.getCurrentStage()).isSameAs(target);
        ArgumentCaptor<LegalProcessStageEventEntity> recorded =
                ArgumentCaptor.forClass(LegalProcessStageEventEntity.class);
        verify(events).saveAndFlush(recorded.capture());
        assertThat(recorded.getValue().getFromStage()).isSameAs(source);
        assertThat(recorded.getValue().getToStage()).isSameAs(target);
        assertThat(recorded.getValue().getActor()).isSameAs(lawyer);
        assertThat(recorded.getValue().getComment()).isEqualTo("Revisión iniciada");
        assertThat(recorded.getValue().getRequestHash()).hasSize(64);
        assertThat(recorded.getValue().getOccurredAt()).isNotNull();

        when(events.findByRequestId(requestId)).thenReturn(Optional.of(recorded.getValue()));
        assertThat(service.move(7L, new MoveRequest(requestId, 2L, 11L, null, "Revisión iniciada"))).isTrue();
        assertThatThrownBy(() -> service.move(7L,
                new MoveRequest(requestId, 3L, 11L, null, "Revisión iniciada")))
                .isInstanceOf(OperationException.class);
        verify(cases, times(1)).saveAndFlush(legalProcess);
        verify(events, times(1)).saveAndFlush(any());
    }

    @Test
    void newCaseCopiesTemplateStagesAndRegistersInitialEvent() {
        LegalProcessEntity legalProcess = legalProcess();
        ProcessTypeEntity type = new ProcessTypeEntity();
        type.setId(3L);
        legalProcess.setProcessType(type);
        ProcessTypeStageEntity definition = new ProcessTypeStageEntity();
        definition.setCode("PRESENTADO");
        definition.setName("Presentado");
        definition.setDisplayOrder(1);
        definition.setInitial(true);
        LegalProcessStageEntity first = stage(10L, "PRESENTADO");
        first.setInitial(true);
        when(templateStages.findByProcessTypeIdOrderByDisplayOrderAsc(3L)).thenReturn(List.of(definition));
        when(templateEdges.findByProcessTypeIdOrderByIdAsc(3L)).thenReturn(List.of());
        when(stages.findByLegalProcessIdOrderByDisplayOrderAsc(7L)).thenReturn(List.of(first));
        when(access.current()).thenReturn(actor());

        service.initializeNew(legalProcess);

        assertThat(legalProcess.getCurrentStage()).isSameAs(first);
        verify(stages).saveAllAndFlush(any());
        ArgumentCaptor<LegalProcessStageEventEntity> recorded =
                ArgumentCaptor.forClass(LegalProcessStageEventEntity.class);
        verify(events).saveAndFlush(recorded.capture());
        assertThat(recorded.getValue().getFromStage()).isNull();
        assertThat(recorded.getValue().getToStage()).isSameAs(first);
        assertThat(recorded.getValue().getRequestId()).isNull();
    }

    @Test
    void rejectsUnconfiguredJumpWithoutChangingCaseOrHistory() {
        LegalProcessEntity legalProcess = legalProcess();
        legalProcess.setCurrentStage(stage(10L, "PRESENTADO"));
        when(access.current()).thenReturn(actor());
        when(cases.findForUpdate(7L)).thenReturn(Optional.of(legalProcess));
        when(stages.existsByLegalProcessId(7L)).thenReturn(true);
        when(stages.findByIdAndLegalProcessId(12L, 7L)).thenReturn(Optional.of(stage(12L, "ENTREGADO")));

        assertThatThrownBy(() -> service.move(7L,
                new MoveRequest(UUID.randomUUID(), 2L, 12L, null, null)))
                .isInstanceOf(OperationException.class);
        assertThat(legalProcess.getCurrentStage().getCode()).isEqualTo("PRESENTADO");
        verify(cases, never()).saveAndFlush(any());
        verify(events, never()).saveAndFlush(any());
    }

    @Test
    void legacyCaseRequiresExplanationAndInitializesAtCurrentTime() {
        LegalProcessEntity legalProcess = legalProcess();
        ProcessTypeEntity type = new ProcessTypeEntity();
        type.setId(3L);
        legalProcess.setProcessType(type);
        ProcessTypeStageEntity definition = new ProcessTypeStageEntity();
        definition.setCode("REVISION");
        definition.setName("En revisión");
        definition.setDisplayOrder(1);
        LegalProcessStageEntity target = stage(11L, "REVISION");
        when(access.current()).thenReturn(actor());
        when(cases.findForUpdate(7L)).thenReturn(Optional.of(legalProcess));
        when(templates.findForUpdate(3L)).thenReturn(Optional.of(type));
        when(templateStages.findByProcessTypeIdOrderByDisplayOrderAsc(3L)).thenReturn(List.of(definition));
        when(templateEdges.findByProcessTypeIdOrderByIdAsc(3L)).thenReturn(List.of());
        when(stages.findByLegalProcessIdAndCode(7L, "REVISION")).thenReturn(Optional.of(target));

        assertThatThrownBy(() -> service.move(7L,
                new MoveRequest(UUID.randomUUID(), 2L, null, "REVISION", null)))
                .isInstanceOf(OperationException.class);
        verify(events, never()).saveAndFlush(any());
    }

    @Test
    void legacyCaseCopiesCurrentDefinitionOnlyWhenExplicitlyInitialized() {
        LegalProcessEntity legalProcess = legalProcess();
        ProcessTypeEntity type = new ProcessTypeEntity();
        type.setId(3L);
        legalProcess.setProcessType(type);
        ProcessTypeStageEntity definition = new ProcessTypeStageEntity();
        definition.setCode("REVISION");
        definition.setName("En revisión");
        definition.setDisplayOrder(1);
        LegalProcessStageEntity target = stage(11L, "REVISION");
        when(access.current()).thenReturn(actor());
        when(cases.findForUpdate(7L)).thenReturn(Optional.of(legalProcess));
        when(templates.findForUpdate(3L)).thenReturn(Optional.of(type));
        when(templateStages.findByProcessTypeIdOrderByDisplayOrderAsc(3L)).thenReturn(List.of(definition));
        when(templateEdges.findByProcessTypeIdOrderByIdAsc(3L)).thenReturn(List.of());
        when(stages.findByLegalProcessIdAndCode(7L, "REVISION")).thenReturn(Optional.of(target));

        assertThat(service.move(7L, new MoveRequest(
                UUID.randomUUID(), 2L, null, "REVISION", "Ya se encuentra en revisión"))).isFalse();
        assertThat(legalProcess.getCurrentStage()).isSameAs(target);
        verify(stages).saveAllAndFlush(any());
        ArgumentCaptor<LegalProcessStageEventEntity> recorded =
                ArgumentCaptor.forClass(LegalProcessStageEventEntity.class);
        verify(events).saveAndFlush(recorded.capture());
        assertThat(recorded.getValue().getFromStage()).isNull();
        assertThat(recorded.getValue().getComment()).isEqualTo("Ya se encuentra en revisión");
    }

    @Test
    void mismatchedReplayKeyIsRejected() {
        LegalProcessEntity legalProcess = legalProcess();
        LegalProcessStageEventEntity previous = new LegalProcessStageEventEntity();
        previous.setLegalProcess(legalProcess);
        previous.setActor(actor());
        previous.setRequestHash("other-content");
        UUID key = UUID.randomUUID();
        when(access.current()).thenReturn(actor());
        when(cases.findForUpdate(7L)).thenReturn(Optional.of(legalProcess));
        when(events.findByRequestId(key)).thenReturn(Optional.of(previous));

        assertThatThrownBy(() -> service.move(7L,
                new MoveRequest(key, 2L, 11L, null, null))).isInstanceOf(OperationException.class);
        verify(cases, never()).saveAndFlush(any());
    }

    private LegalProcessEntity legalProcess() {
        LegalProcessEntity entity = new LegalProcessEntity();
        entity.setId(7L);
        entity.setVersion(2L);
        entity.setActive(true);
        return entity;
    }

    private LegalProcessStageEntity stage(Long id, String code) {
        LegalProcessStageEntity entity = new LegalProcessStageEntity();
        entity.setId(id);
        entity.setCode(code);
        return entity;
    }

    private UserSystemEntity actor() {
        UserSystemEntity entity = new UserSystemEntity();
        entity.setDpi("1234567890123");
        return entity;
    }
}
