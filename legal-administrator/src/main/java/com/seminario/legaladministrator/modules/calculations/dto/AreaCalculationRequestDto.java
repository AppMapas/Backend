package com.seminario.legaladministrator.modules.calculations.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class AreaCalculationRequestDto {
    @NotNull(message = "El DPI del cliente es obligatorio")
    private String clientDpi;

    @NotNull(message = "El DPI del usuario del sistema es obligatorio")
    private String userSystemId;

    @NotBlank(message = "El nombre del terreno es obligatorio")
    private String terrainName;

    private String generalDescription;

    @NotBlank(message = "El tipo de propiedad es obligatorio")
    private String propertyType;

    @NotNull(message = "Debe incluir al menos 3 colindancias")
    private List<BoundaryRequestDto> boundaries;
}