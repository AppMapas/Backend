package com.seminario.legaladministrator.modules.processes.dto;

import com.seminario.legaladministrator.modules.users.dto.ClientUserRequestDto;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class LegalProcessDtos {
    private LegalProcessDtos() { }

    public record CreateRequest(
            @NotNull UUID requestId,
            @Pattern(regexp = "[0-9]{13}") String clientDpi,
            @Valid ClientUserRequestDto client,
            @NotNull @Positive Long processTypeId,
            @NotNull @PositiveOrZero Long processTypeVersion,
            @Size(max = 5000) String generalDetails) {
        @AssertTrue(message = "Indica un cliente existente o los datos de uno nuevo, de forma excluyente.")
        public boolean isClientChoiceValid() {
            return (clientDpi != null) != (client != null);
        }
    }
    public record UpdateRequest(@NotNull @PositiveOrZero Long version,
                                @Size(max = 5000) String generalDetails) { }
    public record Summary(Long id, String caseCode, String clientDpi, String clientName,
                          Long processTypeId, String processTypeName, Long processTypeVersion,
                          String currentStatus, boolean active, String assignedUserDpi,
                          Instant openedAt, Instant modifiedAt, Long version, String generalDetails,
                          Long currentStageId, String currentStageCode, String currentStageName) { }
    public record RequirementResponse(Long id, String name, String description, String instructions,
                                      boolean required, boolean requiresDocument, int displayOrder, String status) { }
    public record Detail(Summary caseData, List<RequirementResponse> requirements,
                         StageDtos.Timeline timeline) { }
    public record Creation(Detail detail, boolean replayed) { }
}
