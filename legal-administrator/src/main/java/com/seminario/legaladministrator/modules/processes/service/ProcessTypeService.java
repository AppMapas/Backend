package com.seminario.legaladministrator.modules.processes.service;

import com.seminario.legaladministrator.modules.processes.ProcessCatalogException;
import com.seminario.legaladministrator.modules.processes.ProcessTypeEntity;
import com.seminario.legaladministrator.modules.processes.ProcessTypeRequirementEntity;
import com.seminario.legaladministrator.modules.processes.ProcessTypeStatus;
import com.seminario.legaladministrator.modules.processes.RequirementEntity;
import com.seminario.legaladministrator.modules.processes.dto.ProcessCatalogDtos.ProcessRequirementRequest;
import com.seminario.legaladministrator.modules.processes.dto.ProcessCatalogDtos.ProcessRequirementResponse;
import com.seminario.legaladministrator.modules.processes.dto.ProcessCatalogDtos.ProcessTypeRequest;
import com.seminario.legaladministrator.modules.processes.dto.ProcessCatalogDtos.ProcessTypeResponse;
import com.seminario.legaladministrator.modules.processes.dto.ProcessCatalogDtos.ProcessTypeSummaryResponse;
import com.seminario.legaladministrator.modules.processes.repository.ProcessTypeRepository;
import com.seminario.legaladministrator.modules.processes.repository.ProcessTypeRequirementRepository;
import com.seminario.legaladministrator.modules.processes.repository.RequirementRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class ProcessTypeService {
    private final ProcessTypeRepository processTypeRepository;
    private final ProcessTypeRequirementRepository linkRepository;
    private final RequirementRepository requirementRepository;

    @Transactional(readOnly = true)
    public List<ProcessTypeSummaryResponse> list() {
        return processTypeRepository.findAll(Sort.by("name")).stream()
                .map(this::toSummary)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<ProcessTypeSummaryResponse> list(ProcessTypeStatus status) {
        if (status == null) return list();
        return processTypeRepository.findByStatusOrderByNameAsc(status).stream().map(this::toSummary).toList();
    }

    @Transactional(readOnly = true)
    public ProcessTypeResponse get(Long id) {
        ProcessTypeEntity processType = findProcessType(id);
        return toResponse(processType);
    }

    @Transactional
    public ProcessTypeResponse create(ProcessTypeRequest request) {
        String name = normalizeName(request.name());
        if (processTypeRepository.existsByNameIgnoreCase(name)) {
            throw new ProcessCatalogException(HttpStatus.CONFLICT, "Ya existe un trámite con ese nombre.");
        }

        ProcessTypeEntity processType = new ProcessTypeEntity();
        processType.setName(name);
        processType.setDescription(normalizeOptionalText(request.description()));
        processType.setStatus(ProcessTypeStatus.DRAFT);
        processType = processTypeRepository.saveAndFlush(processType);

        List<ProcessTypeRequirementEntity> links = buildLinks(processType, request.requirements());
        linkRepository.saveAllAndFlush(links);
        return toResponse(processType);
    }

    @Transactional
    public ProcessTypeResponse update(Long id, ProcessTypeRequest request) {
        ProcessTypeEntity processType = findProcessType(id);
        checkVersion(processType, request.version());
        checkEditable(processType);

        String name = normalizeName(request.name());
        if (processTypeRepository.existsByNameIgnoreCaseAndIdNot(name, id)) {
            throw new ProcessCatalogException(HttpStatus.CONFLICT, "Ya existe un trámite con ese nombre.");
        }

        List<ProcessTypeRequirementEntity> links = buildLinks(processType, request.requirements());
        if (processType.getStatus() == ProcessTypeStatus.PUBLISHED && links.isEmpty()) {
            throw new ProcessCatalogException(HttpStatus.CONFLICT,
                    "Un trámite publicado debe conservar al menos un requisito.");
        }

        processType.setName(name);
        processType.setDescription(normalizeOptionalText(request.description()));
        processType.touch();
        processTypeRepository.saveAndFlush(processType);

        linkRepository.deleteAllByProcessTypeId(id);
        linkRepository.saveAllAndFlush(links);
        return toResponse(processType);
    }

    @Transactional
    public ProcessTypeResponse publish(Long id, Long expectedVersion) {
        ProcessTypeEntity processType = findProcessType(id);
        checkVersion(processType, expectedVersion);
        checkEditable(processType);

        List<ProcessTypeRequirementEntity> links = linkRepository.findByProcessTypeIdOrderByDisplayOrderAsc(id);
        if (links.isEmpty()) {
            throw new ProcessCatalogException(HttpStatus.CONFLICT,
                    "Agrega al menos un requisito antes de publicar el trámite.");
        }
        for (ProcessTypeRequirementEntity link : links) {
            if (!link.getRequirement().isActive()) {
                throw new ProcessCatalogException(HttpStatus.CONFLICT,
                        "No se puede publicar un trámite que contiene requisitos inactivos.");
            }
        }

        if (processType.getStatus() != ProcessTypeStatus.PUBLISHED) {
            processType.setStatus(ProcessTypeStatus.PUBLISHED);
            processType.touch();
            processTypeRepository.saveAndFlush(processType);
        }
        return toResponse(processType);
    }

    @Transactional
    public ProcessTypeResponse deactivate(Long id, Long expectedVersion) {
        ProcessTypeEntity processType = findProcessType(id);
        checkVersion(processType, expectedVersion);

        if (processType.getStatus() != ProcessTypeStatus.INACTIVE) {
            processType.setStatus(ProcessTypeStatus.INACTIVE);
            processType.touch();
            processTypeRepository.saveAndFlush(processType);
        }
        return toResponse(processType);
    }

    private List<ProcessTypeRequirementEntity> buildLinks(
            ProcessTypeEntity processType, List<ProcessRequirementRequest> requests) {
        if (requests == null) {
            throw new ProcessCatalogException(HttpStatus.BAD_REQUEST, "La lista de requisitos es obligatoria.");
        }

        Set<Long> requirementIds = new HashSet<>();
        Set<Integer> orders = new HashSet<>();
        List<ProcessTypeRequirementEntity> links = new ArrayList<>();

        for (ProcessRequirementRequest request : requests) {
            if (request == null || request.requirementId() == null
                    || request.required() == null || request.requiresDocument() == null
                    || request.displayOrder() == null || request.displayOrder() < 1) {
                throw new ProcessCatalogException(HttpStatus.BAD_REQUEST, "Hay un requisito incompleto.");
            }
            if (!requirementIds.add(request.requirementId())) {
                throw new ProcessCatalogException(HttpStatus.BAD_REQUEST, "Un requisito aparece más de una vez.");
            }
            if (!orders.add(request.displayOrder())) {
                throw new ProcessCatalogException(HttpStatus.BAD_REQUEST, "El orden de los requisitos está repetido.");
            }

            RequirementEntity requirement = requirementRepository.findById(request.requirementId())
                    .orElseThrow(() -> new ProcessCatalogException(
                            HttpStatus.NOT_FOUND, "Uno de los requisitos no existe."));
            if (!requirement.isActive()) {
                throw new ProcessCatalogException(HttpStatus.CONFLICT, "No se pueden asociar requisitos inactivos.");
            }

            ProcessTypeRequirementEntity link = new ProcessTypeRequirementEntity();
            link.setProcessType(processType);
            link.setRequirement(requirement);
            link.setRequired(request.required());
            link.setRequiresDocument(request.requiresDocument());
            link.setDisplayOrder(request.displayOrder());
            link.setInstructions(normalizeOptionalText(request.instructions()));
            links.add(link);
        }
        return links;
    }

    private ProcessTypeEntity findProcessType(Long id) {
        return processTypeRepository.findById(id)
                .orElseThrow(() -> new ProcessCatalogException(
                        HttpStatus.NOT_FOUND, "Trámite no encontrado."));
    }

    private void checkVersion(ProcessTypeEntity processType, Long expectedVersion) {
        if (expectedVersion == null) {
            throw new ProcessCatalogException(HttpStatus.BAD_REQUEST, "La versión del trámite es obligatoria.");
        }
        if (!expectedVersion.equals(processType.getVersion())) {
            throw new ProcessCatalogException(HttpStatus.CONFLICT,
                    "El trámite cambió desde la última consulta. Recarga su información.");
        }
    }

    private void checkEditable(ProcessTypeEntity processType) {
        if (processType.getStatus() == ProcessTypeStatus.INACTIVE) {
            throw new ProcessCatalogException(HttpStatus.CONFLICT,
                    "Un trámite inactivo no puede editarse ni publicarse.");
        }
    }

    private String normalizeName(String value) {
        if (value == null || value.isBlank()) {
            throw new ProcessCatalogException(HttpStatus.BAD_REQUEST, "El nombre del trámite es obligatorio.");
        }
        String name = value.trim();
        if (name.length() > 100) {
            throw new ProcessCatalogException(HttpStatus.BAD_REQUEST, "El nombre del trámite es demasiado largo.");
        }
        return name;
    }

    private String normalizeOptionalText(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private ProcessTypeSummaryResponse toSummary(ProcessTypeEntity processType) {
        return new ProcessTypeSummaryResponse(
                processType.getId(),
                processType.getName(),
                processType.getDescription(),
                processType.getStatus(),
                processType.getVersion());
    }

    private ProcessTypeResponse toResponse(ProcessTypeEntity processType) {
        List<ProcessRequirementResponse> requirements = linkRepository
                .findByProcessTypeIdOrderByDisplayOrderAsc(processType.getId()).stream()
                .map(link -> new ProcessRequirementResponse(
                        link.getRequirement().getId(),
                        link.getRequirement().getName(),
                        link.getRequirement().getDescription(),
                        link.isRequired(),
                        link.isRequiresDocument(),
                        link.getDisplayOrder(),
                        link.getInstructions()))
                .toList();

        return new ProcessTypeResponse(
                processType.getId(),
                processType.getName(),
                processType.getDescription(),
                processType.getStatus(),
                processType.getVersion(),
                processType.getCreatedAt(),
                processType.getUpdatedAt(),
                requirements);
    }
}
