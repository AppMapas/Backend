package com.seminario.legaladministrator.modules.processes.service;

import com.seminario.legaladministrator.modules.processes.ProcessCatalogException;
import com.seminario.legaladministrator.modules.processes.ProcessTypeEntity;
import com.seminario.legaladministrator.modules.processes.ProcessTypeRequirementEntity;
import com.seminario.legaladministrator.modules.processes.ProcessTypeStatus;
import com.seminario.legaladministrator.modules.processes.RequirementEntity;
import com.seminario.legaladministrator.modules.processes.dto.ProcessCatalogDtos.ProcessRequirementRequest;
import com.seminario.legaladministrator.modules.processes.dto.ProcessCatalogDtos.ProcessTypeRequest;
import com.seminario.legaladministrator.modules.processes.repository.ProcessTypeRepository;
import com.seminario.legaladministrator.modules.processes.repository.ProcessTypeRequirementRepository;
import com.seminario.legaladministrator.modules.processes.repository.RequirementRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProcessTypeServiceTest {
    @Mock
    private ProcessTypeRepository processTypeRepository;
    @Mock
    private ProcessTypeRequirementRepository linkRepository;
    @Mock
    private RequirementRepository requirementRepository;
    @Mock
    private StageConfigurationService stageConfiguration;

    private ProcessTypeService service;

    @BeforeEach
    void setUp() {
        service = new ProcessTypeService(processTypeRepository, linkRepository,
                requirementRepository, stageConfiguration);
    }

    @Test
    void createsDraftWithOrderedRequirements() {
        RequirementEntity dpi = requirement(10L, "Copia de DPI", true);
        AtomicReference<List<ProcessTypeRequirementEntity>> savedLinks =
                new AtomicReference<>(List.of());

        when(requirementRepository.findById(10L)).thenReturn(Optional.of(dpi));
        when(processTypeRepository.saveAndFlush(any())).thenAnswer(invocation -> {
            ProcessTypeEntity entity = invocation.getArgument(0);
            entity.setId(4L);
            entity.setVersion(0L);
            entity.setCreatedAt(Instant.now());
            entity.setUpdatedAt(Instant.now());
            return entity;
        });
        when(linkRepository.saveAllAndFlush(any())).thenAnswer(invocation -> {
            Iterable<ProcessTypeRequirementEntity> links = invocation.getArgument(0);
            List<ProcessTypeRequirementEntity> result = new ArrayList<>();
            links.forEach(result::add);
            savedLinks.set(result);
            return result;
        });
        when(linkRepository.findByProcessTypeIdOrderByDisplayOrderAsc(4L))
                .thenAnswer(invocation -> savedLinks.get());

        ProcessTypeRequest request = new ProcessTypeRequest(
                " Titulación supletoria ", " Expediente de propiedad ",
                List.of(new ProcessRequirementRequest(10L, true, true, 1, " Documento legible ")),
                null);

        var response = service.create(request);

        assertThat(response.name()).isEqualTo("Titulación supletoria");
        assertThat(response.status()).isEqualTo(ProcessTypeStatus.DRAFT);
        assertThat(response.requirements()).hasSize(1);
        assertThat(response.requirements().get(0).name()).isEqualTo("Copia de DPI");
        assertThat(response.requirements().get(0).instructions()).isEqualTo("Documento legible");
    }

    @Test
    void rejectsRepeatedRequirementPosition() {
        when(processTypeRepository.saveAndFlush(any())).thenAnswer(invocation -> {
            ProcessTypeEntity entity = invocation.getArgument(0);
            entity.setId(4L);
            return entity;
        });
        when(requirementRepository.findById(10L))
                .thenReturn(Optional.of(requirement(10L, "DPI", true)));

        ProcessTypeRequest request = new ProcessTypeRequest("Trámite", null, List.of(
                new ProcessRequirementRequest(10L, true, false, 1, null),
                new ProcessRequirementRequest(11L, true, false, 1, null)), null);

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOf(ProcessCatalogException.class)
                .satisfies(error -> assertThat(((ProcessCatalogException) error).getStatus())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
        verify(linkRepository, never()).saveAllAndFlush(any());
    }

    @Test
    void rejectsStaleUpdateBeforeReplacingRequirements() {
        ProcessTypeEntity entity = processType(4L, 2L, ProcessTypeStatus.DRAFT);
        when(processTypeRepository.findById(4L)).thenReturn(Optional.of(entity));

        ProcessTypeRequest request = new ProcessTypeRequest("Trámite", null, List.of(), 1L);

        assertThatThrownBy(() -> service.update(4L, request))
                .isInstanceOf(ProcessCatalogException.class)
                .satisfies(error -> assertThat(((ProcessCatalogException) error).getStatus())
                        .isEqualTo(HttpStatus.CONFLICT));
        verify(linkRepository, never()).deleteAllByProcessTypeId(4L);
    }

    @Test
    void replacesRequirementsAndReturnsUpdatedVersion() {
        ProcessTypeEntity entity = processType(4L, 2L, ProcessTypeStatus.DRAFT);
        AtomicReference<List<ProcessTypeRequirementEntity>> savedLinks =
                new AtomicReference<>(List.of());
        when(processTypeRepository.findById(4L)).thenReturn(Optional.of(entity));
        when(requirementRepository.findById(11L))
                .thenReturn(Optional.of(requirement(11L, "Plano", true)));
        when(processTypeRepository.saveAndFlush(entity)).thenAnswer(invocation -> {
            entity.setVersion(3L);
            return entity;
        });
        when(linkRepository.saveAllAndFlush(any())).thenAnswer(invocation -> {
            Iterable<ProcessTypeRequirementEntity> links = invocation.getArgument(0);
            List<ProcessTypeRequirementEntity> result = new ArrayList<>();
            links.forEach(result::add);
            savedLinks.set(result);
            return result;
        });
        when(linkRepository.findByProcessTypeIdOrderByDisplayOrderAsc(4L))
                .thenAnswer(invocation -> savedLinks.get());

        ProcessTypeRequest request = new ProcessTypeRequest(
                "Trámite actualizado", null,
                List.of(new ProcessRequirementRequest(11L, true, false, 1, null)),
                2L);

        var response = service.update(4L, request);

        assertThat(response.version()).isEqualTo(3L);
        assertThat(response.requirements()).extracting(item -> item.name()).containsExactly("Plano");
        verify(linkRepository).deleteAllByProcessTypeId(4L);
    }

    @Test
    void rejectsRepeatedRequirementEvenWithDifferentPositions() {
        when(processTypeRepository.saveAndFlush(any())).thenAnswer(invocation -> {
            ProcessTypeEntity entity = invocation.getArgument(0);
            entity.setId(4L);
            return entity;
        });
        when(requirementRepository.findById(10L))
                .thenReturn(Optional.of(requirement(10L, "DPI", true)));

        ProcessTypeRequest request = new ProcessTypeRequest("Trámite", null, List.of(
                new ProcessRequirementRequest(10L, true, false, 1, null),
                new ProcessRequirementRequest(10L, true, false, 2, null)), null);

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOf(ProcessCatalogException.class)
                .satisfies(error -> assertThat(((ProcessCatalogException) error).getStatus())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
        verify(linkRepository, never()).saveAllAndFlush(any());
    }

    @Test
    void publishedTemplateCannotLoseAllRequirements() {
        ProcessTypeEntity entity = processType(4L, 2L, ProcessTypeStatus.PUBLISHED);
        when(processTypeRepository.findById(4L)).thenReturn(Optional.of(entity));

        ProcessTypeRequest request = new ProcessTypeRequest("Trámite", null, List.of(), 2L);

        assertThatThrownBy(() -> service.update(4L, request))
                .isInstanceOf(ProcessCatalogException.class)
                .satisfies(error -> assertThat(((ProcessCatalogException) error).getStatus())
                        .isEqualTo(HttpStatus.CONFLICT));
        verify(linkRepository, never()).deleteAllByProcessTypeId(4L);
    }

    @Test
    void cannotPublishWithInactiveRequirement() {
        ProcessTypeEntity entity = processType(4L, 2L, ProcessTypeStatus.DRAFT);
        ProcessTypeRequirementEntity link = new ProcessTypeRequirementEntity();
        link.setRequirement(requirement(10L, "DPI", false));
        when(processTypeRepository.findById(4L)).thenReturn(Optional.of(entity));
        when(linkRepository.findByProcessTypeIdOrderByDisplayOrderAsc(4L))
                .thenReturn(List.of(link));

        assertThatThrownBy(() -> service.publish(4L, 2L))
                .isInstanceOf(ProcessCatalogException.class)
                .satisfies(error -> assertThat(((ProcessCatalogException) error).getStatus())
                        .isEqualTo(HttpStatus.CONFLICT));
        verify(processTypeRepository, never()).saveAndFlush(any());
    }

    @Test
    void publishesDraftWithActiveRequirements() {
        ProcessTypeEntity entity = processType(4L, 2L, ProcessTypeStatus.DRAFT);
        ProcessTypeRequirementEntity link = new ProcessTypeRequirementEntity();
        link.setRequirement(requirement(10L, "DPI", true));
        link.setDisplayOrder(1);
        when(processTypeRepository.findById(4L)).thenReturn(Optional.of(entity));
        when(linkRepository.findByProcessTypeIdOrderByDisplayOrderAsc(4L))
                .thenReturn(List.of(link));
        when(processTypeRepository.saveAndFlush(entity)).thenReturn(entity);

        var response = service.publish(4L, 2L);

        assertThat(response.status()).isEqualTo(ProcessTypeStatus.PUBLISHED);
        verify(processTypeRepository).saveAndFlush(entity);
    }

    private ProcessTypeEntity processType(Long id, Long version, ProcessTypeStatus status) {
        ProcessTypeEntity entity = new ProcessTypeEntity();
        entity.setId(id);
        entity.setVersion(version);
        entity.setName("Trámite");
        entity.setStatus(status);
        return entity;
    }

    private RequirementEntity requirement(Long id, String name, boolean active) {
        RequirementEntity entity = new RequirementEntity();
        entity.setId(id);
        entity.setName(name);
        entity.setActive(active);
        return entity;
    }
}
