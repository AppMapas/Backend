package com.seminario.legaladministrator.modules.calculations.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class ConversionResponseDto {
    private Double originalValue;
    private String unit;
    private Double convertedValueMeters;
}