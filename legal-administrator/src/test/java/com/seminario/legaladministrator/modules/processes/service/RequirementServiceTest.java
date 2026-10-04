package com.seminario.legaladministrator.modules.processes.service;

import com.seminario.legaladministrator.modules.processes.ProcessCatalogException;
import com.seminario.legaladministrator.modules.processes.ProcessTypeStatus;
import com.seminario.legaladministrator.modules.processes.RequirementEntity;
import com.seminario.legaladministrator.modules.processes.dto.ProcessCatalogDtos.RequirementRequest;
import com.seminario.legaladministrator.modules.processes.repository.ProcessTypeRequirementRepository;
import com.seminario.legaladministrator.modules.processes.repository.RequirementRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RequirementServiceTest {
    @Mock
    private RequirementRepository requirementRepository;
    @Mock
    private ProcessTypeRequirementRepository linkRepository;

    private RequirementService service;

    @BeforeEach
    void setUp() {
        service = new RequirementService(requirementRepository, linkRepository);
    }

    @Test
    void createsNormalizedActiveRequirement() {
        when(requirementRepository.saveAndFlush(any())).thenAnswer(invocation -> {
            RequirementEntity entity = invocation.getArgument(0);
            entity.setId(8L);
            return entity;
        });

        var response = service.create(new RequirementRequest(" Copia de DPI ", " Documento vigente "));

        assertThat(response.id()).isEqualTo(8L);
        assertThat(response.name()).isEqualTo("Copia de DPI");
        assertThat(response.description()).isEqualTo("Documento vigente");
        assertThat(response.active()).isTrue();
    }

    @Test
    void rejectsDuplicateName() {
        when(requirementRepository.existsByNameIgnoreCase("Copia de DPI")).thenReturn(true);

        assertThatThrownBy(() -> service.create(new RequirementRequest("Copia de DPI", null)))
                .isInstanceOf(ProcessCatalogException.class)
                .satisfies(error -> assertThat(((ProcessCatalogException) error).getStatus())
                        .isEqualTo(HttpStatus.CONFLICT));
        verify(requirementRepository, never()).saveAndFlush(any());
    }

    @Test
    void doesNotChangeRequirementUsedByPublishedTemplate() {
        RequirementEntity requirement = new RequirementEntity();
        requirement.setId(8L);
        requirement.setName("Copia de DPI");
        requirement.setActive(true);
        when(requirementRepository.findById(8L)).thenReturn(Optional.of(requirement));
        when(linkRepository.existsByRequirementIdAndProcessTypeStatus(8L, ProcessTypeStatus.PUBLISHED))
                .thenReturn(true);

        assertThatThrownBy(() -> service.update(8L, new RequirementRequest("DPI actualizado", null)))
                .isInstanceOf(ProcessCatalogException.class)
                .satisfies(error -> assertThat(((ProcessCatalogException) error).getStatus())
                        .isEqualTo(HttpStatus.CONFLICT));
        verify(requirementRepository, never()).saveAndFlush(any());
    }

    @Test
    void doesNotDeactivateRequirementUsedByPublishedTemplate() {
        RequirementEntity requirement = new RequirementEntity();
        requirement.setId(8L);
        requirement.setName("Copia de DPI");
        requirement.setActive(true);
        when(requirementRepository.findById(8L)).thenReturn(Optional.of(requirement));
        when(linkRepository.existsByRequirementIdAndProcessTypeStatus(8L, ProcessTypeStatus.PUBLISHED))
                .thenReturn(true);

        assertThatThrownBy(() -> service.deactivate(8L))
                .isInstanceOf(ProcessCatalogException.class)
                .satisfies(error -> assertThat(((ProcessCatalogException) error).getStatus())
                        .isEqualTo(HttpStatus.CONFLICT));
        verify(requirementRepository, never()).saveAndFlush(any());
    }
}
