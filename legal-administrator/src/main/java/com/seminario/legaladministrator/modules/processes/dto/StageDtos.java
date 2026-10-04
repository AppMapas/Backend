package com.seminario.legaladministrator.modules.processes.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class StageDtos {
    private StageDtos() { }

    public record StageInput(@NotBlank String code, @NotBlank String name,
                             @NotNull @Positive Integer displayOrder,
                             @NotNull Boolean initial, @NotNull Boolean terminal) { }
    public record EdgeInput(@NotBlank String fromCode, @NotBlank String toCode) { }
    public record ConfigureRequest(@NotNull @PositiveOrZero Long version,
                                   @NotNull List<@Valid StageInput> stages,
                                   @NotNull List<@Valid EdgeInput> transitions) { }
    public record StageDefinition(Long id, String code, String name, int displayOrder,
                                  boolean initial, boolean terminal) { }
    public record EdgeDefinition(String fromCode, String toCode) { }
    public record Configuration(Long version, List<StageDefinition> stages,
                                List<EdgeDefinition> transitions) { }

    public record MoveRequest(@NotNull UUID requestId, @NotNull @PositiveOrZero Long version,
                              @Positive Long targetStageId,
                              @Pattern(regexp = "[A-Z][A-Z0-9_]{1,39}") String targetStageCode,
                              @Size(max = 1000) String comment) {
        @AssertTrue(message = "Indica el identificador o el código de la etapa de destino.")
        public boolean isTargetValid() {
            return (targetStageId != null) != (targetStageCode != null);
        }
    }
    public record StageView(Long id, String code, String name, int displayOrder,
                            boolean initial, boolean terminal) { }
    public record EventView(Long id, Long fromStageId, Long toStageId, String actorDpi,
                            Instant occurredAt, String comment) { }
    public record Timeline(StageView currentStage, List<StageView> stages,
                           List<Long> allowedNextStageIds, List<EventView> events) { }
}
