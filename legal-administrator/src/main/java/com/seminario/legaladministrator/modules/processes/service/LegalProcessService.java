package com.seminario.legaladministrator.modules.processes.service;

import com.seminario.legaladministrator.config.security.OfficeAccess;
import com.seminario.legaladministrator.modules.processes.*;
import com.seminario.legaladministrator.modules.processes.dto.LegalProcessDtos.*;
import com.seminario.legaladministrator.modules.processes.repository.*;
import com.seminario.legaladministrator.modules.users.ClientUserEntity;
import com.seminario.legaladministrator.modules.users.repository.ClientUserRepository;
import com.seminario.legaladministrator.modules.users.service.ClientUserService;
import com.seminario.legaladministrator.shared.*;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.Objects;
import java.util.Comparator;

@Service
@RequiredArgsConstructor
@PreAuthorize("hasAnyAuthority('Abogada', 'Administrador') and @officeAccess.allowed(authentication)")
public class LegalProcessService {
    private final LegalProcessRepository cases;
    private final LegalProcessRequirementRepository requirements;
    private final ProcessTypeRepository templates;
    private final ProcessTypeRequirementRepository templateRequirements;
    private final ClientUserRepository clients;
    private final ClientUserService clientService;
    private final OfficeAccess officeAccess;
    private final CaseRequestGuard requestGuard;
    private final Validator validator;
    private final StageWorkflowService stageWorkflow;

    @Transactional(timeout = 20)
    public Creation create(CreateRequest request) {
        validate(request);
        var operator = officeAccess.current();
        String fingerprint = requestGuard.fingerprint(request);
        requestGuard.lock(request.requestId());
        var previous = cases.findByRequestId(request.requestId());
        if (previous.isPresent()) {
            LegalProcessEntity existing = previous.get();
            if (existing.getCreatedBy() == null
                    || !operator.getDpi().equals(existing.getCreatedBy().getDpi())
                    || !fingerprint.equals(existing.getRequestHash())) {
                throw new OperationException(HttpStatus.CONFLICT,
                        "La clave de solicitud ya se utilizó. Reutilízala únicamente para reintentar el mismo registro.");
            }
            return new Creation(detail(existing), true);
        }

        ProcessTypeEntity template = templates.findForUpdate(request.processTypeId())
                .orElseThrow(() -> new OperationException(HttpStatus.NOT_FOUND, "Trámite no encontrado."));
        if (template.getStatus() != ProcessTypeStatus.PUBLISHED) {
            throw new OperationException(HttpStatus.CONFLICT, "Selecciona un trámite publicado.");
        }
        if (!Objects.equals(template.getVersion(), request.processTypeVersion())) {
            throw new OperationException(HttpStatus.CONFLICT, "El trámite cambió. Consulta su configuración nuevamente.");
        }
        List<ProcessTypeRequirementEntity> links =
                templateRequirements.findByProcessTypeIdOrderByDisplayOrderAsc(template.getId());
        if (links.isEmpty()) {
            throw new OperationException(HttpStatus.CONFLICT, "El trámite publicado no tiene requisitos configurados.");
        }
        links.stream().map(ProcessTypeRequirementEntity::getRequirement)
                .sorted(Comparator.comparing(RequirementEntity::getId))
                .forEach(requestGuard::lockRequirement);
        if (links.stream().anyMatch(link -> !link.getRequirement().isActive())) {
            throw new OperationException(HttpStatus.CONFLICT, "El trámite contiene requisitos inactivos.");
        }

        ClientUserEntity client;
        if (request.client() != null) {
            client = clientService.createEntity(request.client());
        } else {
            client = clients.findForUpdate(request.clientDpi())
                    .orElseThrow(() -> new OperationException(HttpStatus.NOT_FOUND, "Cliente no encontrado."));
        }
        if (!client.isActive()) {
            throw new OperationException(HttpStatus.CONFLICT, "El cliente está inactivo.");
        }
        if (client.getNationality() == null || client.getMaritalStatus() == null
                || client.getExactAddress() == null || client.getExactAddress().isBlank()) {
            throw new OperationException(HttpStatus.CONFLICT,
                    "Completa la nacionalidad, el estado civil y la dirección del cliente antes de abrir un expediente.");
        }

        LegalProcessEntity entity = new LegalProcessEntity();
        entity.setCaseCode(cases.nextCaseCode());
        entity.setClient(client);
        entity.setAssignedUser(operator);
        entity.setCreatedBy(operator);
        entity.setProcessType(template);
        entity.setProcessTypeNameSnapshot(template.getName());
        entity.setProcessTypeVersionSnapshot(template.getVersion());
        entity.setGeneralDetails(InputRules.text(request.generalDetails()));
        entity.setRequestId(request.requestId());
        entity.setRequestHash(fingerprint);
        entity = cases.saveAndFlush(entity);
        LegalProcessEntity saved = entity;
        requirements.saveAllAndFlush(links.stream().map(link -> snapshot(saved, link)).toList());
        stageWorkflow.initializeNew(saved);
        return new Creation(detail(saved), false);
    }

    @Transactional(readOnly = true)
    public Detail get(Long id) {
        return detail(find(id));
    }

    @Transactional
    public Detail update(Long id, UpdateRequest request) {
        validate(request);
        if (id == null || id < 1) {
            throw new OperationException(HttpStatus.BAD_REQUEST, "Identificador no válido.");
        }
        LegalProcessEntity entity = cases.findForUpdate(id)
                .orElseThrow(() -> new OperationException(HttpStatus.NOT_FOUND, "Expediente no encontrado."));
        if (!Objects.equals(entity.getVersion(), request.version())) {
            throw new OperationException(HttpStatus.CONFLICT, "El expediente cambió. Recarga su información.");
        }
        if (!entity.isActive()) {
            throw new OperationException(HttpStatus.CONFLICT, "El expediente está inactivo.");
        }
        entity.setGeneralDetails(InputRules.text(request.generalDetails()));
        return detail(cases.saveAndFlush(entity));
    }

    @Transactional(readOnly = true)
    public PageResponse<Summary> search(String query, String clientDpi, Long typeId,
                                        String status, Boolean active, int page, int size) {
        var pagination = InputRules.page(page, size,
                Sort.by(Sort.Order.desc("openedAt"), Sort.Order.desc("id")));
        String term = InputRules.search(query);
        if (clientDpi != null) {
            InputRules.dpi(clientDpi);
        }
        if (typeId != null && typeId < 1) {
            throw new OperationException(HttpStatus.BAD_REQUEST, "Tipo de trámite no válido.");
        }
        String normalizedStatus = InputRules.text(status);
        if (normalizedStatus != null && normalizedStatus.length() > 50) {
            throw new OperationException(HttpStatus.BAD_REQUEST, "Estado no válido.");
        }
        Specification<LegalProcessEntity> criteria = (root, cq, cb) -> {
            var match = cb.conjunction();
            var client = root.join("client");
            if (!term.isEmpty()) {
                for (String token : term.split("\\s+")) {
                    match = cb.and(match, cb.or(
                            cb.greaterThan(cb.locate(cb.lower(root.get("caseCode")), token), 0),
                            cb.greaterThan(cb.locate(client.get("dpi"), token), 0),
                            cb.greaterThan(cb.locate(cb.lower(client.get("firstName")), token), 0),
                            cb.greaterThan(cb.locate(cb.lower(client.get("lastName")), token), 0)));
                }
            }
            if (clientDpi != null) {
                match = cb.and(match, cb.equal(client.get("dpi"), clientDpi));
            }
            if (typeId != null) {
                match = cb.and(match, cb.equal(root.get("processType").get("id"), typeId));
            }
            if (normalizedStatus != null) {
                match = cb.and(match, cb.equal(root.get("currentStatus"), normalizedStatus));
            }
            if (active != null) {
                match = cb.and(match, cb.equal(root.get("active"), active));
            }
            return match;
        };
        return PageResponse.from(cases.findAll(criteria, pagination).map(this::summary));
    }

    private void validate(Object request) {
        if (request == null || !validator.validate(request).isEmpty()) {
            throw new OperationException(HttpStatus.BAD_REQUEST, "La solicitud está incompleta o contiene datos no válidos.");
        }
    }

    private LegalProcessEntity find(Long id) {
        if (id == null || id < 1) {
            throw new OperationException(HttpStatus.BAD_REQUEST, "Identificador no válido.");
        }
        return cases.findById(id).orElseThrow(() -> new OperationException(HttpStatus.NOT_FOUND, "Expediente no encontrado."));
    }

    private LegalProcessRequirementEntity snapshot(LegalProcessEntity entity, ProcessTypeRequirementEntity link) {
        var result = new LegalProcessRequirementEntity();
        result.setLegalProcess(entity);
        result.setSourceRequirement(link.getRequirement());
        result.setNameSnapshot(link.getRequirement().getName());
        result.setDescriptionSnapshot(link.getRequirement().getDescription());
        result.setInstructionsSnapshot(link.getInstructions());
        result.setRequiredSnapshot(link.isRequired());
        result.setRequiresDocumentSnapshot(link.isRequiresDocument());
        result.setDisplayOrder(link.getDisplayOrder());
        return result;
    }

    private Summary summary(LegalProcessEntity entity) {
        var client = entity.getClient();
        Long stageId = null;
        String stageCode = null;
        String stageName = null;
        if (entity.getCurrentStage() != null) {
            stageId = entity.getCurrentStage().getId();
            stageCode = entity.getCurrentStage().getCode();
            stageName = entity.getCurrentStage().getNameSnapshot();
        }
        return new Summary(entity.getId(), entity.getCaseCode(), client.getDpi(),
                client.getFirstName() + " " + client.getLastName(), entity.getProcessType().getId(),
                entity.getProcessTypeNameSnapshot(), entity.getProcessTypeVersionSnapshot(),
                entity.getCurrentStatus(), entity.isActive(), entity.getAssignedUser().getDpi(),
                entity.getOpenedAt(), entity.getModifiedAt(), entity.getVersion(), entity.getGeneralDetails(),
                stageId, stageCode, stageName);
    }

    private Detail detail(LegalProcessEntity entity) {
        var items = requirements.findByLegalProcessIdOrderByDisplayOrderAsc(entity.getId()).stream()
                .map(r -> new RequirementResponse(r.getId(), r.getNameSnapshot(), r.getDescriptionSnapshot(),
                        r.getInstructionsSnapshot(), r.isRequiredSnapshot(), r.isRequiresDocumentSnapshot(),
                        r.getDisplayOrder(), r.getStatus())).toList();
        return new Detail(summary(entity), items, stageWorkflow.timeline(entity));
    }
}
