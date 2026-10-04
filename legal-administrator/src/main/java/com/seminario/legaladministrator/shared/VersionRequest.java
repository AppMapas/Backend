package com.seminario.legaladministrator.shared;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
public record VersionRequest(@NotNull @PositiveOrZero Long version) { }
