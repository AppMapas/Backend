package com.seminario.legaladministrator.hu07;

import com.seminario.legaladministrator.modules.processes.dto.StageDtos.*;
import com.seminario.legaladministrator.modules.processes.service.StageGraphValidator;
import com.seminario.legaladministrator.shared.OperationException;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class StageGraphValidatorTest {
    private final StageGraphValidator validator = new StageGraphValidator();
    private final List<StageInput> stages = List.of(
            new StageInput("PRESENTADO", "Presentado", 1, true, false),
            new StageInput("REVISION", "En revisión", 2, false, false),
            new StageInput("ENTREGADO", "Entregado", 3, false, true));
    private final List<EdgeInput> path = List.of(
            new EdgeInput("PRESENTADO", "REVISION"),
            new EdgeInput("REVISION", "ENTREGADO"));

    @Test
    void acceptsOrderedFlowAndExplicitReturn() {
        assertThatCode(() -> validator.validate(stages, path)).doesNotThrowAnyException();
        assertThatCode(() -> validator.validate(stages, List.of(
                new EdgeInput("PRESENTADO", "REVISION"),
                new EdgeInput("REVISION", "PRESENTADO"),
                new EdgeInput("REVISION", "ENTREGADO")))).doesNotThrowAnyException();
    }

    @Test
    void rejectsUnreachableStageAndTerminalDeparture() {
        assertThatThrownBy(() -> validator.validate(stages, List.of(new EdgeInput("PRESENTADO", "ENTREGADO"))))
                .isInstanceOf(OperationException.class);
        assertThatThrownBy(() -> validator.validate(stages, List.of(
                new EdgeInput("PRESENTADO", "REVISION"), new EdgeInput("REVISION", "ENTREGADO"),
                new EdgeInput("ENTREGADO", "REVISION")))).isInstanceOf(OperationException.class);
    }

    @Test
    void rejectsDuplicateNamesCodesOrdersAndMissingInitial() {
        assertThatThrownBy(() -> validator.validate(List.of(
                stages.get(0), new StageInput("REVISION", "presentado", 2, false, true)), path))
                .isInstanceOf(OperationException.class);
        assertThatThrownBy(() -> validator.validate(List.of(
                new StageInput("A", "A", 1, false, false),
                new StageInput("B", "B", 2, false, true)), List.of(new EdgeInput("A", "B"))))
                .isInstanceOf(OperationException.class);
        assertThatThrownBy(() -> validator.validate(List.of(
                stages.get(0), new StageInput("REVISION", "En revisión", 1, false, false), stages.get(2)), path))
                .isInstanceOf(OperationException.class);
    }
}
