package com.seminario.legaladministrator.modules.calculations.dto;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class BoundaryRequestDto {
    @NotNull(message = "El número de lado es obligatorio")
    private Long sideNumber;
    private String referencePoint;
    private String orientation;

    @NotNull(message = "Las medidas de colindancia son obligatorias")
    private List<MeasurementRequestDto> measurements;
}
