package com.seminario.legaladministrator.modules.processes.service;

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
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

@Service
@RequiredArgsConstructor
@PreAuthorize("hasAnyAuthority('Abogada', 'Administrador') and @officeAccess.allowed(authentication)")
public class StageConfigurationService {
    private final ProcessTypeRepository types;
    private final ProcessTypeStageRepository stages;
    private final ProcessTypeStageTransitionRepository transitions;
    private final StageGraphValidator graph;

    @Transactional(readOnly = true)
    public Configuration get(Long typeId) {
        ProcessTypeEntity type = types.findById(typeId)
                .orElseThrow(() -> new OperationException(HttpStatus.NOT_FOUND, "Trámite no encontrado."));
        return response(type);
    }

    @Transactional
    public Configuration configure(Long typeId, ConfigureRequest request) {
        if (request == null || request.version() == null) {
            throw new OperationException(HttpStatus.BAD_REQUEST, "La versión del trámite es obligatoria.");
        }
        ProcessTypeEntity type = types.findForUpdate(typeId)
                .orElseThrow(() -> new OperationException(HttpStatus.NOT_FOUND, "Trámite no encontrado."));
        if (type.getStatus() == ProcessTypeStatus.INACTIVE) {
            throw new OperationException(HttpStatus.CONFLICT, "Un trámite inactivo no puede configurarse.");
        }
        if (!Objects.equals(type.getVersion(), request.version())) {
            throw new OperationException(HttpStatus.CONFLICT, "El trámite cambió. Recarga su configuración.");
        }
        graph.validate(request.stages(), request.transitions());
        transitions.deleteByProcessTypeId(typeId);
        transitions.flush();
        stages.deleteByProcessTypeId(typeId);
        stages.flush();
        List<ProcessTypeStageEntity> configured = request.stages().stream().map(item -> {
            ProcessTypeStageEntity stage = new ProcessTypeStageEntity();
            stage.setProcessType(type);
            stage.setCode(item.code());
            stage.setName(InputRules.text(item.name()));
            stage.setDisplayOrder(item.displayOrder());
            stage.setInitial(item.initial());
            stage.setTerminal(item.terminal());
            return stage;
        }).toList();
        stages.saveAllAndFlush(configured);
        transitions.saveAllAndFlush(request.transitions().stream().map(edge -> {
            ProcessTypeStageTransitionEntity transition = new ProcessTypeStageTransitionEntity();
            transition.setProcessType(type);
            transition.setFromCode(edge.fromCode());
            transition.setToCode(edge.toCode());
            return transition;
        }).toList());
        type.touch();
        types.saveAndFlush(type);
        return response(type);
    }

    public void requireConfigured(Long typeId) {
        List<ProcessTypeStageEntity> configured = stages.findByProcessTypeIdOrderByDisplayOrderAsc(typeId);
        if (configured.isEmpty()) {
            throw new OperationException(HttpStatus.CONFLICT,
                    "Configura las etapas antes de publicar el trámite.");
        }
    }

    private Configuration response(ProcessTypeEntity type) {
        List<StageDefinition> definitions = stages.findByProcessTypeIdOrderByDisplayOrderAsc(type.getId())
                .stream().map(stage -> new StageDefinition(stage.getId(), stage.getCode(), stage.getName(),
                        stage.getDisplayOrder(), stage.isInitial(), stage.isTerminal())).toList();
        List<EdgeDefinition> edges = transitions.findByProcessTypeIdOrderByIdAsc(type.getId())
                .stream().map(edge -> new EdgeDefinition(edge.getFromCode(), edge.getToCode()))
                .sorted(Comparator.comparing(EdgeDefinition::fromCode).thenComparing(EdgeDefinition::toCode))
                .toList();
        return new Configuration(type.getVersion(), definitions, edges);
    }
}
