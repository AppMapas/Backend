package com.seminario.legaladministrator.modules.calculations.dto;

import lombok.*;

import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SplitLineDto {
    private String cutName;          // Ej: "Paso de Servidumbre Norte", "Servidumbre de Esquina", etc.
    private String divisionType;     // Ej: "SERVAGUAS", "SERVIDUMBRE_TRANSITO", "SUB_LOTE"
    private List<CoordinateDto> points;
    private List<BoundaryRequestDto> boundaries;
}