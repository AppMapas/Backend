package com.seminario.legaladministrator.modules.calculations.dto;

import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PolygonSplitRequestDto {
    @NotNull(message = "El ID del cálculo principal es obligatorio")
    private Long parentCalculationId;

    @NotNull(message = "Debe especificar al menos una línea de corte o división")
    private List<SplitLineDto> splitLines;
}