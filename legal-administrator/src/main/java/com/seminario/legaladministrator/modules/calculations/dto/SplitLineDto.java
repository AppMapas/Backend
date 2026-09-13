package com.seminario.legaladministrator.modules.calculations.dto;

import lombok.*;

import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SplitLineDto {
    private String cutName;
    private List<CoordinateDto> points;
}