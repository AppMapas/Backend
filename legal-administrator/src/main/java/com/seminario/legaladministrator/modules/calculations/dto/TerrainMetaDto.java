package com.seminario.legaladministrator.modules.calculations.dto;

import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TerrainMetaDto {
    private String unit;
    private String generatedAt;
    private boolean closed;
    private boolean calculated;
}