package com.seminario.legaladministrator.modules.processes.service;

import com.seminario.legaladministrator.modules.processes.dto.StageDtos.*;
import com.seminario.legaladministrator.shared.InputRules;
import com.seminario.legaladministrator.shared.OperationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
public class StageGraphValidator {
    public void validate(List<StageInput> stages, List<EdgeInput> transitions) {
        if (stages == null || transitions == null || stages.size() < 2 || stages.size() > 40) {
            invalid("Configura entre 2 y 40 etapas y sus transiciones.");
        }
        Map<String, StageInput> byCode = new HashMap<>();
        Set<Integer> positions = new HashSet<>();
        Set<String> names = new HashSet<>();
        String initialCode = null;
        Set<String> terminalCodes = new HashSet<>();
        for (StageInput stage : stages) {
            if (stage == null || stage.code() == null || stage.name() == null
                    || stage.displayOrder() == null || stage.initial() == null || stage.terminal() == null) {
                invalid("Hay una etapa incompleta.");
            }
            String name = InputRules.text(stage.name());
            if (!stage.code().matches("[A-Z][A-Z0-9_]{1,39}") || name == null || name.length() > 150
                    || stage.displayOrder() < 1 || !positions.add(stage.displayOrder())
                    || !names.add(name.toLowerCase(Locale.ROOT))
                    || byCode.putIfAbsent(stage.code(), stage) != null) {
                invalid("Las etapas necesitan códigos, nombres y posiciones válidas y únicas.");
            }
            if (stage.initial()) {
                if (initialCode != null) invalid("Solo puede existir una etapa inicial.");
                initialCode = stage.code();
            }
            if (stage.terminal()) terminalCodes.add(stage.code());
        }
        if (initialCode == null || terminalCodes.isEmpty()) {
            invalid("Define una etapa inicial y al menos una etapa final.");
        }
        for (int order = 1; order <= stages.size(); order++) {
            if (!positions.contains(order)) invalid("Las posiciones deben ser consecutivas desde 1.");
        }
        Map<String, Set<String>> forward = new HashMap<>();
        Map<String, Set<String>> reverse = new HashMap<>();
        Set<String> uniqueEdges = new HashSet<>();
        for (EdgeInput edge : transitions) {
            if (edge == null || edge.fromCode() == null || edge.toCode() == null
                    || !byCode.containsKey(edge.fromCode()) || !byCode.containsKey(edge.toCode())
                    || edge.fromCode().equals(edge.toCode()) || terminalCodes.contains(edge.fromCode())
                    || !uniqueEdges.add(edge.fromCode() + ":" + edge.toCode())) {
                invalid("Hay una transición repetida, inválida o que sale de una etapa final.");
            }
            forward.computeIfAbsent(edge.fromCode(), ignored -> new HashSet<>()).add(edge.toCode());
            reverse.computeIfAbsent(edge.toCode(), ignored -> new HashSet<>()).add(edge.fromCode());
        }
        if (!reachable(Set.of(initialCode), forward).containsAll(byCode.keySet())
                || !reachable(terminalCodes, reverse).containsAll(byCode.keySet())) {
            invalid("Todas las etapas deben ser alcanzables y conducir a una etapa final.");
        }
    }

    private Set<String> reachable(Set<String> roots, Map<String, Set<String>> graph) {
        Set<String> found = new HashSet<>(roots);
        Deque<String> pending = new ArrayDeque<>(roots);
        while (!pending.isEmpty()) {
            for (String next : graph.getOrDefault(pending.removeFirst(), Set.of())) {
                if (found.add(next)) pending.addLast(next);
            }
        }
        return found;
    }

    private void invalid(String message) {
        throw new OperationException(HttpStatus.BAD_REQUEST, message);
    }
}
