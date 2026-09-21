package com.seminario.legaladministrator.modules.calculations.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
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

    @NotBlank(message = "La ubicación es obligatoria.")
    private String location;

    private String planImageBase64;

    private TerrainMetaDto meta;

    @NotNull(message = "Debe incluir los vértices del plano")
    @Size(min = 3, message = "El polígono debe tener al menos 3 vértices")
    private List<CoordinateDto> vertices;

    @NotNull(message = "Debe incluir al menos 3 colindancias")
    private List<BoundaryRequestDto> boundaries;
}