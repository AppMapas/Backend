package com.seminario.legaladministrator.modules.processes.dto;

import com.seminario.legaladministrator.modules.processes.ProcessTypeStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

public final class ProcessCatalogDtos {
    private ProcessCatalogDtos() {
    }

    public record RequirementRequest(
            @NotBlank @Size(max = 150) String name,
            String description
    ) {
    }

    public record RequirementResponse(
            Long id,
            String name,
            String description,
            boolean active,
            Instant createdAt,
            Instant updatedAt
    ) {
    }

    public record ProcessRequirementRequest(
            @NotNull Long requirementId,
            @NotNull Boolean required,
            @NotNull Boolean requiresDocument,
            @NotNull @Min(1) Integer displayOrder,
            String instructions
    ) {
    }

    public record ProcessTypeRequest(
            @NotBlank @Size(max = 100) String name,
            @Size(max = 255) String description,
            @NotNull List<@Valid ProcessRequirementRequest> requirements,
            Long version
    ) {
    }

    public record VersionRequest(@NotNull Long version) {
    }

    public record ProcessRequirementResponse(
            Long requirementId,
            String name,
            String description,
            boolean required,
            boolean requiresDocument,
            int displayOrder,
            String instructions
    ) {
    }

    public record ProcessTypeSummaryResponse(
            Long id,
            String name,
            String description,
            ProcessTypeStatus status,
            Long version
    ) {
    }

    public record ProcessTypeResponse(
            Long id,
            String name,
            String description,
            ProcessTypeStatus status,
            Long version,
            Instant createdAt,
            Instant updatedAt,
            List<ProcessRequirementResponse> requirements
    ) {
    }
}
