package com.seminario.legaladministrator.modules.processes.service;

import com.seminario.legaladministrator.modules.processes.ProcessCatalogException;
import com.seminario.legaladministrator.modules.processes.ProcessTypeStatus;
import com.seminario.legaladministrator.modules.processes.RequirementEntity;
import com.seminario.legaladministrator.modules.processes.dto.ProcessCatalogDtos.RequirementRequest;
import com.seminario.legaladministrator.modules.processes.dto.ProcessCatalogDtos.RequirementResponse;
import com.seminario.legaladministrator.modules.processes.repository.ProcessTypeRequirementRepository;
import com.seminario.legaladministrator.modules.processes.repository.RequirementRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class RequirementService {
    private final RequirementRepository requirementRepository;
    private final ProcessTypeRequirementRepository linkRepository;

    @Transactional(readOnly = true)
    public List<RequirementResponse> list() {
        return requirementRepository.findAll(Sort.by("name")).stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public RequirementResponse get(Long id) {
        return toResponse(findRequirement(id));
    }

    @Transactional
    public RequirementResponse create(RequirementRequest request) {
        String name = normalizeName(request.name());
        if (requirementRepository.existsByNameIgnoreCase(name)) {
            throw new ProcessCatalogException(HttpStatus.CONFLICT, "Ya existe un requisito con ese nombre.");
        }

        RequirementEntity requirement = new RequirementEntity();
        requirement.setName(name);
        requirement.setDescription(normalizeOptionalText(request.description()));
        requirement.setActive(true);
        return toResponse(requirementRepository.saveAndFlush(requirement));
    }

    @Transactional
    public RequirementResponse update(Long id, RequirementRequest request) {
        RequirementEntity requirement = findRequirement(id);
        String name = normalizeName(request.name());
        String description = normalizeOptionalText(request.description());

        if (requirementRepository.existsByNameIgnoreCaseAndIdNot(name, id)) {
            throw new ProcessCatalogException(HttpStatus.CONFLICT, "Ya existe un requisito con ese nombre.");
        }

        boolean contentChanged = !requirement.getName().equals(name);
        if (!Objects.equals(requirement.getDescription(), description)) {
            contentChanged = true;
        }
        if (contentChanged && usedByPublishedTemplate(id)) {
            throw new ProcessCatalogException(HttpStatus.CONFLICT,
                    "El requisito pertenece a una plantilla publicada. Crea una variante y reemplaza la asociación.");
        }

        requirement.setName(name);
        requirement.setDescription(description);
        return toResponse(requirementRepository.saveAndFlush(requirement));
    }

    @Transactional
    public RequirementResponse deactivate(Long id) {
        RequirementEntity requirement = findRequirement(id);
        if (!requirement.isActive()) {
            return toResponse(requirement);
        }
        if (usedByPublishedTemplate(id)) {
            throw new ProcessCatalogException(HttpStatus.CONFLICT,
                    "Quita este requisito de las plantillas publicadas antes de desactivarlo.");
        }

        requirement.setActive(false);
        return toResponse(requirementRepository.saveAndFlush(requirement));
    }

    private boolean usedByPublishedTemplate(Long requirementId) {
        return linkRepository.existsByRequirementIdAndProcessTypeStatus(
                requirementId, ProcessTypeStatus.PUBLISHED);
    }

    private RequirementEntity findRequirement(Long id) {
        return requirementRepository.findById(id)
                .orElseThrow(() -> new ProcessCatalogException(
                        HttpStatus.NOT_FOUND, "Requisito no encontrado."));
    }

    private String normalizeName(String value) {
        if (value == null || value.isBlank()) {
            throw new ProcessCatalogException(HttpStatus.BAD_REQUEST, "El nombre del requisito es obligatorio.");
        }
        String name = value.trim();
        if (name.length() > 150) {
            throw new ProcessCatalogException(HttpStatus.BAD_REQUEST, "El nombre del requisito es demasiado largo.");
        }
        return name;
    }

    private String normalizeOptionalText(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private RequirementResponse toResponse(RequirementEntity requirement) {
        return new RequirementResponse(
                requirement.getId(),
                requirement.getName(),
                requirement.getDescription(),
                requirement.isActive(),
                requirement.getCreatedAt(),
                requirement.getUpdatedAt());
    }
}
