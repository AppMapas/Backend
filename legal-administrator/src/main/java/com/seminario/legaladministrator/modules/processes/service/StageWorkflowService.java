package com.seminario.legaladministrator.modules.processes.service;

import com.seminario.legaladministrator.config.security.OfficeAccess;
import com.seminario.legaladministrator.modules.processes.*;
import com.seminario.legaladministrator.modules.processes.dto.StageDtos.*;
import com.seminario.legaladministrator.modules.processes.repository.*;
import com.seminario.legaladministrator.shared.InputRules;
import com.seminario.legaladministrator.shared.OperationException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.*;

@Service
@RequiredArgsConstructor
@PreAuthorize("hasAnyAuthority('Abogada', 'Administrador') and @officeAccess.allowed(authentication)")
public class StageWorkflowService {
    private final LegalProcessRepository cases;
    private final ProcessTypeRepository templates;
    private final ProcessTypeStageRepository templateStages;
    private final ProcessTypeStageTransitionRepository templateEdges;
    private final LegalProcessStageRepository stages;
    private final LegalProcessStageTransitionRepository edges;
    private final LegalProcessStageEventRepository events;
    private final OfficeAccess officeAccess;

    @Transactional
    public void initializeNew(LegalProcessEntity legalProcess) {
        List<ProcessTypeStageEntity> definitions = templateStages
                .findByProcessTypeIdOrderByDisplayOrderAsc(legalProcess.getProcessType().getId());
        if (definitions.isEmpty()) {
            throw new OperationException(HttpStatus.CONFLICT, "El trámite no tiene etapas configuradas.");
        }
        copyDefinitions(legalProcess, definitions);
        LegalProcessStageEntity initial = stages.findByLegalProcessIdOrderByDisplayOrderAsc(legalProcess.getId())
                .stream().filter(LegalProcessStageEntity::isInitial).findFirst()
                .orElseThrow(() -> new OperationException(HttpStatus.CONFLICT, "No existe etapa inicial."));
        legalProcess.setCurrentStage(initial);
        cases.saveAndFlush(legalProcess);
        events.saveAndFlush(event(legalProcess, null, initial, officeAccess.current(), null, null, null));
    }

    private void copyDefinitions(LegalProcessEntity legalProcess, List<ProcessTypeStageEntity> definitions) {
        Long caseId = legalProcess.getId();
        stages.saveAllAndFlush(definitions.stream().map(template -> {
            LegalProcessStageEntity stage = new LegalProcessStageEntity();
            stage.setLegalProcess(legalProcess);
            stage.setCode(template.getCode());
            stage.setNameSnapshot(template.getName());
            stage.setDisplayOrder(template.getDisplayOrder());
            stage.setInitial(template.isInitial());
            stage.setTerminal(template.isTerminal());
            return stage;
        }).toList());
        edges.saveAllAndFlush(templateEdges.findByProcessTypeIdOrderByIdAsc(
                legalProcess.getProcessType().getId()).stream().map(template -> {
            LegalProcessStageTransitionEntity edge = new LegalProcessStageTransitionEntity();
            edge.setLegalProcess(legalProcess);
            edge.setFromCode(template.getFromCode());
            edge.setToCode(template.getToCode());
            return edge;
        }).toList());
    }

    @Transactional(timeout = 20)
    public boolean move(Long caseId, MoveRequest request) {
        if (caseId == null || caseId < 1 || request == null || request.requestId() == null
                || request.version() == null || request.version() < 0
                || !request.isTargetValid()) {
            throw new OperationException(HttpStatus.BAD_REQUEST, "Solicitud de cambio de etapa incompleta.");
        }
        String comment = InputRules.text(request.comment());
        if (comment != null && comment.length() > 1000) {
            throw new OperationException(HttpStatus.BAD_REQUEST, "La observación admite hasta 1000 caracteres.");
        }
        var actor = officeAccess.current();
        LegalProcessEntity legalProcess = cases.findForUpdate(caseId)
                .orElseThrow(() -> new OperationException(HttpStatus.NOT_FOUND, "Expediente no encontrado."));
        String fingerprint = fingerprint(caseId, request.version(),
                request.targetStageId(), request.targetStageCode(), comment);
        Optional<LegalProcessStageEventEntity> previous = events.findByRequestId(request.requestId());
        if (previous.isPresent()) {
            LegalProcessStageEventEntity existing = previous.get();
            if (!existing.getLegalProcess().getId().equals(caseId)
                    || !existing.getActor().getDpi().equals(actor.getDpi())
                    || !fingerprint.equals(existing.getRequestHash())) {
                throw new OperationException(HttpStatus.CONFLICT, "La clave de solicitud ya se utilizó.");
            }
            return true;
        }
        if (!legalProcess.isActive()) {
            throw new OperationException(HttpStatus.CONFLICT, "El expediente está inactivo.");
        }
        if (!Objects.equals(legalProcess.getVersion(), request.version())) {
            throw new OperationException(HttpStatus.CONFLICT, "El expediente cambió. Recarga su información.");
        }
        if (!stages.existsByLegalProcessId(caseId)) {
            templates.findForUpdate(legalProcess.getProcessType().getId())
                    .orElseThrow(() -> new OperationException(HttpStatus.NOT_FOUND, "Trámite no encontrado."));
            List<ProcessTypeStageEntity> definitions = templateStages
                    .findByProcessTypeIdOrderByDisplayOrderAsc(legalProcess.getProcessType().getId());
            if (definitions.isEmpty()) {
                throw new OperationException(HttpStatus.CONFLICT, "Configura las etapas del trámite antes de iniciar este expediente.");
            }
            copyDefinitions(legalProcess, definitions);
        }
        LegalProcessStageEntity target;
        if (request.targetStageId() != null) {
            target = stages.findByIdAndLegalProcessId(request.targetStageId(), caseId)
                    .orElseThrow(() -> new OperationException(HttpStatus.BAD_REQUEST,
                            "La etapa no pertenece a este expediente."));
        } else {
            if (!request.targetStageCode().matches("[A-Z][A-Z0-9_]{1,39}")) {
                throw new OperationException(HttpStatus.BAD_REQUEST, "Código de etapa no válido.");
            }
            target = stages.findByLegalProcessIdAndCode(caseId, request.targetStageCode())
                    .orElseThrow(() -> new OperationException(HttpStatus.BAD_REQUEST,
                            "La etapa no pertenece a este expediente."));
        }
        LegalProcessStageEntity source = legalProcess.getCurrentStage();
        if (source != null && request.targetStageCode() != null) {
            throw new OperationException(HttpStatus.BAD_REQUEST,
                    "Utiliza el identificador de etapa para avanzar el expediente.");
        }
        if (source == null && comment == null) {
            throw new OperationException(HttpStatus.BAD_REQUEST, "Explica la etapa elegida para el expediente anterior.");
        }
        if (source != null && (source.isTerminal() || !edges.existsByLegalProcessIdAndFromCodeAndToCode(
                caseId, source.getCode(), target.getCode()))) {
            throw new OperationException(HttpStatus.CONFLICT, "La transición de etapa no está permitida.");
        }
        legalProcess.setCurrentStage(target);
        cases.saveAndFlush(legalProcess);
        events.saveAndFlush(event(legalProcess, source, target, actor, comment,
                request.requestId(), fingerprint));
        return false;
    }

    @Transactional(readOnly = true)
    public Timeline timeline(LegalProcessEntity legalProcess) {
        List<LegalProcessStageEntity> configured = stages
                .findByLegalProcessIdOrderByDisplayOrderAsc(legalProcess.getId());
        List<StageView> views = configured.stream().map(this::view).toList();
        LegalProcessStageEntity current = legalProcess.getCurrentStage();
        if (views.isEmpty() && current == null) {
            views = templateStages.findByProcessTypeIdOrderByDisplayOrderAsc(
                    legalProcess.getProcessType().getId()).stream()
                    .map(stage -> new StageView(null, stage.getCode(), stage.getName(),
                            stage.getDisplayOrder(), stage.isInitial(), stage.isTerminal())).toList();
        }
        StageView currentView = null;
        List<Long> allowed = List.of();
        if (current != null) {
            currentView = view(current);
            Set<String> permittedCodes = new HashSet<>();
            for (LegalProcessStageTransitionEntity edge : edges.findByLegalProcessIdOrderByIdAsc(legalProcess.getId())) {
                if (edge.getFromCode().equals(current.getCode())) permittedCodes.add(edge.getToCode());
            }
            allowed = views.stream().filter(stage -> permittedCodes.contains(stage.code()))
                    .map(StageView::id).toList();
        } else if (!configured.isEmpty()) {
            allowed = views.stream().map(StageView::id).toList();
        }
        List<EventView> history = events.findByLegalProcessIdOrderByOccurredAtAscIdAsc(legalProcess.getId())
                .stream().map(item -> {
                    Long fromId = null;
                    if (item.getFromStage() != null) fromId = item.getFromStage().getId();
                    return new EventView(item.getId(), fromId, item.getToStage().getId(),
                            item.getActor().getDpi(), item.getOccurredAt(), item.getComment());
                }).toList();
        return new Timeline(currentView, views, allowed, history);
    }

    private StageView view(LegalProcessStageEntity stage) {
        return new StageView(stage.getId(), stage.getCode(), stage.getNameSnapshot(),
                stage.getDisplayOrder(), stage.isInitial(), stage.isTerminal());
    }

    private LegalProcessStageEventEntity event(LegalProcessEntity legalProcess,
            LegalProcessStageEntity source, LegalProcessStageEntity target,
            com.seminario.legaladministrator.modules.users.UserSystemEntity actor,
            String comment, UUID requestId, String requestHash) {
        LegalProcessStageEventEntity entry = new LegalProcessStageEventEntity();
        entry.setLegalProcess(legalProcess);
        entry.setFromStage(source);
        entry.setToStage(target);
        entry.setActor(actor);
        entry.setOccurredAt(Instant.now());
        entry.setComment(comment);
        entry.setRequestId(requestId);
        entry.setRequestHash(requestHash);
        return entry;
    }

    private String fingerprint(Long caseId, Long version, Long targetId, String targetCode, String comment) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String canonical = caseId + "|" + version + "|" + Objects.toString(targetId, "") + "|"
                    + Objects.toString(targetCode, "") + "|" + Objects.toString(comment, "");
            byte[] bytes = digest.digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 no disponible.", error);
        }
    }
}
