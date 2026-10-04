package com.seminario.legaladministrator.hu07;

import com.seminario.legaladministrator.modules.processes.ProcessTypeEntity;
import com.seminario.legaladministrator.modules.processes.ProcessTypeStatus;
import com.seminario.legaladministrator.modules.processes.dto.StageDtos.*;
import com.seminario.legaladministrator.modules.processes.repository.*;
import com.seminario.legaladministrator.modules.processes.service.*;
import com.seminario.legaladministrator.shared.OperationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.util.List;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StageConfigurationServiceTest {
    @Mock ProcessTypeRepository types;
    @Mock ProcessTypeStageRepository stages;
    @Mock ProcessTypeStageTransitionRepository transitions;
    StageConfigurationService service;

    @BeforeEach
    void setUp() {
        service = new StageConfigurationService(types, stages, transitions, new StageGraphValidator());
    }

    @Test
    void replacesDefinitionUnderTemplateLockAndBumpsVersion() {
        ProcessTypeEntity template = new ProcessTypeEntity();
        template.setId(5L);
        template.setVersion(3L);
        template.setStatus(ProcessTypeStatus.PUBLISHED);
        when(types.findForUpdate(5L)).thenReturn(Optional.of(template));
        ConfigureRequest request = new ConfigureRequest(3L, List.of(
                new StageInput("PRESENTADO", "Presentado", 1, true, false),
                new StageInput("ENTREGADO", "Entregado", 2, false, true)),
                List.of(new EdgeInput("PRESENTADO", "ENTREGADO")));

        service.configure(5L, request);

        verify(transitions).deleteByProcessTypeId(5L);
        verify(stages).deleteByProcessTypeId(5L);
        verify(stages).saveAllAndFlush(any());
        verify(transitions).saveAllAndFlush(any());
        verify(types).saveAndFlush(template);
    }

    @Test
    void staleVersionCannotReplacePublishedFlow() {
        ProcessTypeEntity template = new ProcessTypeEntity();
        template.setId(5L);
        template.setVersion(4L);
        template.setStatus(ProcessTypeStatus.PUBLISHED);
        when(types.findForUpdate(5L)).thenReturn(Optional.of(template));

        assertThatThrownBy(() -> service.configure(5L,
                new ConfigureRequest(3L, List.of(), List.of()))).isInstanceOf(OperationException.class);
        verifyNoInteractions(stages, transitions);
    }
}
